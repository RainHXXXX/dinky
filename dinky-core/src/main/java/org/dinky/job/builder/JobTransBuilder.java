/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.job.builder;

import org.dinky.assertion.Asserts;
import org.dinky.constant.FlinkSQLConstant;
import org.dinky.data.enums.GatewayType;
import org.dinky.data.job.SqlType;
import org.dinky.data.result.IResult;
import org.dinky.data.result.InsertResult;
import org.dinky.data.result.ResultBuilder;
import org.dinky.data.result.SqlExplainResult;
import org.dinky.executor.Executor;
import org.dinky.gateway.Gateway;
import org.dinky.gateway.result.GatewayResult;
import org.dinky.interceptor.FlinkInterceptor;
import org.dinky.interceptor.FlinkInterceptorResult;
import org.dinky.job.Job;
import org.dinky.job.JobBuilder;
import org.dinky.job.JobConfig;
import org.dinky.job.JobManager;
import org.dinky.job.StatementParam;
import org.dinky.utils.LogUtil;
import org.dinky.utils.SqlUtil;
import org.dinky.utils.URLUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.rest.messages.JobPlanInfo;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.table.api.TableResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import cn.hutool.core.text.StrFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * JobTransBuilder
 */
@Slf4j
public class JobTransBuilder extends JobBuilder {

    public JobTransBuilder(JobManager jobManager) {
        super(jobManager);
    }

    public static JobTransBuilder build(JobManager jobManager) {
        return new JobTransBuilder(jobManager);
    }

    @Override
    public void run() throws Exception {
        if (jobParam.getTrans().isEmpty()) {
            return;
        }

        if (inferStatementSet()) {
            handleStatementSet();
        } else {
            handleNonStatementSet();
        }
    }

    @Override
    public List<SqlExplainResult> explain() {
        List<SqlExplainResult> sqlExplainResults = new ArrayList<>();
        if (Asserts.isNullCollection(jobParam.getTrans())) {
            return sqlExplainResults;
        }
        if (inferStatementSet()) {
            List<String> inserts = new ArrayList<>();
            for (StatementParam item : jobParam.getTrans()) {
                if (item.getType().equals(SqlType.INSERT) || item.getType().equals(SqlType.CTAS)) {
                    inserts.add(item.getValue());
                }
            }
            if (!inserts.isEmpty()) {
                SqlExplainResult.Builder resultBuilder = SqlExplainResult.Builder.newBuilder();
                String sqlSet = StringUtils.join(inserts, ";\r");
                try {
                    resultBuilder.explain(null).parseTrue(true).explainTrue(true);
                } catch (Exception e) {
                    String error = LogUtil.getError(e);
                    resultBuilder
                            .type(SqlType.INSERT.getType())
                            .error(error)
                            .parseTrue(false)
                            .explainTrue(false);
                    log.error(error);
                } finally {
                    resultBuilder
                            .type(SqlType.INSERT.getType())
                            .explainTime(LocalDateTime.now())
                            .sql(sqlSet);
                    sqlExplainResults.add(resultBuilder.build());
                }
            }
        } else {
            for (StatementParam item : jobParam.getTrans()) {
                SqlExplainResult.Builder resultBuilder = SqlExplainResult.Builder.newBuilder();
                try {
                    resultBuilder = SqlExplainResult.newBuilder(executor.explainSqlRecord(item.getValue()));
                    resultBuilder.parseTrue(true).explainTrue(true);
                } catch (Exception e) {
                    String error = StrFormatter.format(
                            "Exception in explaining FlinkSQL:\n{}\n{}",
                            SqlUtil.addLineNumber(item.getValue()),
                            e.getMessage());
                    resultBuilder
                            .type(item.getType().getType())
                            .error(error)
                            .parseTrue(false)
                            .explainTrue(false);
                    log.error(error);
                } finally {
                    resultBuilder
                            .type(item.getType().getType())
                            .explainTime(LocalDateTime.now())
                            .sql(item.getValue());
                    sqlExplainResults.add(resultBuilder.build());
                }
            }
        }
        return sqlExplainResults;
    }

    @Override
    public StreamGraph getStreamGraph() {
        return executor.getStreamGraphFromStatement(null);
    }

    @Override
    public JobPlanInfo getJobPlanInfo() {
        return executor.getJobPlanInfo(null);
    }

    private boolean inferStatementSet() {
        boolean hasInsert = false;
        for (StatementParam item : jobParam.getTrans()) {
            if (item.getType().equals(SqlType.INSERT)) {
                hasInsert = true;
                break;
            }
        }
        return hasInsert;
    }

    private void handleStatementSet() throws Exception {
        List<String> inserts =
                jobParam.getTrans().stream().map(StatementParam::getValue).collect(Collectors.toList());
        if (useGateway) {
            processWithGateway(inserts);
            return;
        }
        processWithoutGateway(inserts);
    }

    private void handleNonStatementSet() throws Exception {
        if (useGateway) {
            processSingleInsertWithGateway();
            return;
        }
        processFirstStatement();
    }

    private void processWithGateway(List<String> inserts) throws Exception {
        jobManager.setCurrentSql(String.join(FlinkSQLConstant.SEPARATOR, inserts));
        GatewayResult gatewayResult = submitByGateway(inserts);
        setJobResultFromGatewayResult(gatewayResult);
    }

    private void processWithoutGateway(List<String> inserts) throws Exception {
        if (!inserts.isEmpty()) {
            jobManager.setCurrentSql(String.join(FlinkSQLConstant.SEPARATOR, inserts));
            TableResult tableResult = executor.executeStatementSet(inserts);
            updateJobWithTableResult(tableResult);
        }
    }

    private void processSingleInsertWithGateway() throws Exception {
        List<String> singleInsert =
                Collections.singletonList(jobParam.getTrans().get(0).getValue());
        job.setPipeline(jobParam.getTrans().get(0).getType().isPipeline());
        processWithGateway(singleInsert);
    }

    private void processFirstStatement() throws Exception {
        if (jobParam.getTrans().isEmpty()) {
            return;
        }
        // Only process the first statement when not using statement set
        StatementParam item = jobParam.getTrans().get(0);
        job.setPipeline(item.getType().isPipeline());
        jobManager.setCurrentSql(item.getValue());
        processSingleStatement(item);
    }

    private void processSingleStatement(StatementParam item) throws Exception {
        FlinkInterceptorResult flinkInterceptorResult = FlinkInterceptor.build(executor, item.getValue());
        if (Asserts.isNotNull(flinkInterceptorResult.getTableResult())) {
            updateJobWithTableResult(flinkInterceptorResult.getTableResult(), item.getType());
        } else if (!flinkInterceptorResult.isNoExecute()) {
            TableResult tableResult = executor.executeSql(item.getValue());
            updateJobWithTableResult(tableResult, item.getType());
        }
    }

    private void setJobResultFromGatewayResult(GatewayResult gatewayResult) {
        job.setResult(InsertResult.success(gatewayResult.getId()));
        job.setJobId(gatewayResult.getId());
        job.setJids(gatewayResult.getJids());
        job.setJobManagerAddress(URLUtils.formatAddress(gatewayResult.getWebURL()));
        job.setStatus(gatewayResult.isSuccess() ? Job.JobStatus.SUCCESS : Job.JobStatus.FAILED);
        if (!gatewayResult.isSuccess()) {
            job.setError(gatewayResult.getError());
        }
    }

    private void updateJobWithTableResult(TableResult tableResult) {
        updateJobWithTableResult(tableResult, SqlType.INSERT);
    }

    private void updateJobWithTableResult(TableResult tableResult, SqlType sqlType) {
        if (tableResult.getJobClient().isPresent()) {
            job.setJobId(tableResult.getJobClient().get().getJobID().toHexString());
            job.setJids(Collections.singletonList(job.getJobId()));
        } else if (!sqlType.getCategory().getHasJobClient()) {
            job.setJobId(UUID.randomUUID().toString().replace("-", ""));
            job.setJids(Collections.singletonList(job.getJobId()));
        }

        if (config.isUseResult()) {
            IResult result = ResultBuilder.build(
                            sqlType,
                            job.getId().toString(),
                            config.getMaxRowNum(),
                            config.isUseChangeLog(),
                            config.isUseAutoCancel(),
                            executor.getTimeZone(),
                            jobManager.getConfig().isMockSinkFunction())
                    .getResultWithPersistence(tableResult, jobManager.getHandler());
            job.setResult(result);
        }
    }

    private GatewayResult submitByGateway(List<String> inserts) {
        JobConfig config = jobManager.getConfig();
        GatewayType runMode = jobManager.getRunMode();
        Executor executor = jobManager.getExecutor();

        GatewayResult gatewayResult = null;

        // Use gateway need to build gateway config, include flink configuration.
        config.addGatewayConfig(executor.getCustomTableEnvironment().getConfig().getConfiguration());
        config.getGatewayConfig().setSql(jobParam.getParsedSql());
        if (runMode.isApplicationMode()) {
            // Application mode need to submit dinky-app.jar that in the hdfs or image.
            gatewayResult = Gateway.build(config.getGatewayConfig())
                    .submitJar(executor.getDinkyClassLoader().getUdfPathContextHolder());
        } else {
            JobGraph jobGraph = executor.getJobGraphFromInserts(null);
            // Perjob mode need to set savepoint restore path, when recovery from savepoint.
            if (Asserts.isNotNullString(config.getSavePointPath())) {
                jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forPath(config.getSavePointPath(), true));
            }
            // Perjob mode need to submit job graph.
            gatewayResult = Gateway.build(config.getGatewayConfig()).submitJobGraph(jobGraph);
        }
        return gatewayResult;
    }
}
