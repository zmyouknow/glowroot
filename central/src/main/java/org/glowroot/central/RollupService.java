/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AgentRollupDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final AgentRollupDao agentRollupDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;

    private final ExecutorService executor;

    private volatile boolean closed;

    RollupService(AgentRollupDao agentRollupDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, SyntheticResultDao syntheticResultDao,
            CentralAlertingService centralAlertingService, Clock clock) {
        this.agentRollupDao = agentRollupDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.syntheticResultDao = syntheticResultDao;
        this.centralAlertingService = centralAlertingService;
        this.clock = clock;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                Thread.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                runInternal();
            } catch (InterruptedException e) {
                continue;
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to RollupService thread
        executor.shutdownNow();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for rollup thread to terminate");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer rollup loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal() throws Exception {
        Glowroot.setTransactionOuter();
        for (AgentRollup agentRollup : agentRollupDao.readAgentRollups()) {
            rollupAggregates(agentRollup, null);
            rollupGauges(agentRollup, null);
            rollupSyntheticMonitors(agentRollup);
            // checking for deleted alerts doesn't depend on rollup
            consumeAgentRollups(agentRollup, this::checkForDeletedAlerts);
            // checking aggregate and gauge alerts after rollup since their calculation can depend
            // on rollups depending on time period length
            //
            // alerts are also checked right after receiving the respective data
            // (aggregate/gauge/heartbeat) from agent, but need to also check once a minute in
            // case no data has been received from agent recently
            consumeAgentRollups(agentRollup, this::checkAggregateAndGaugeAndHeartbeatAlertsAsync);
        }
    }

    private void rollupAggregates(AgentRollup agentRollup, @Nullable String parentAgentRollupId)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupAggregates(childAgentRollup, agentRollup.id());
        }
        try {
            aggregateDao.rollup(agentRollup.id(), parentAgentRollupId,
                    agentRollup.children().isEmpty());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    // returns true on success, false on failure
    private boolean rollupGauges(AgentRollup agentRollup, @Nullable String parentAgentRollupId)
            throws InterruptedException {
        // important to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        boolean success = true;
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            boolean childSuccess = rollupGauges(childAgentRollup, agentRollup.id());
            success = success && childSuccess;
        }
        if (!success) {
            // also important to not roll up parent if exception occurs while rolling up a child,
            // since gauge values initial roll up from children is done on the 1-min aggregates of
            // the children
            return false;
        }
        try {
            gaugeValueDao.rollup(agentRollup.id(), parentAgentRollupId,
                    agentRollup.children().isEmpty());
            return true;
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
            return false;
        }
    }

    private void rollupSyntheticMonitors(AgentRollup agentRollup) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupSyntheticMonitors(childAgentRollup);
        }
        try {
            syntheticResultDao.rollup(agentRollup.id());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    private void consumeAgentRollups(AgentRollup agentRollup,
            AgentRollupConsumer agentRollupConsumer) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            consumeAgentRollups(childAgentRollup, agentRollupConsumer);
        }
        agentRollupConsumer.accept(agentRollup);
    }

    private void checkForDeletedAlerts(AgentRollup agentRollup) throws Exception {
        centralAlertingService.checkForDeletedAlerts(agentRollup.id());
    }

    private void checkAggregateAndGaugeAndHeartbeatAlertsAsync(AgentRollup agentRollup)
            throws Exception {
        centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup.id(),
                agentRollup.display(), clock.currentTimeMillis());
    }

    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }

    @FunctionalInterface
    interface AgentRollupConsumer {
        void accept(AgentRollup agentRollup) throws Exception;
    }
}
