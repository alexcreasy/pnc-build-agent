/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.jboss.pnc.buildagent.websockets;

import org.jboss.pnc.buildagent.MockProcess;
import org.jboss.pnc.buildagent.TermdServer;
import org.jboss.pnc.buildagent.api.ResponseMode;
import org.jboss.pnc.buildagent.api.Status;
import org.jboss.pnc.buildagent.api.TaskStatusUpdateEvent;
import org.jboss.pnc.buildagent.client.BuildAgentClient;
import org.jboss.pnc.buildagent.common.ObjectWrapper;
import org.jboss.pnc.buildagent.common.Wait;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;


/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TestWebSocketConnection {

    private static final Logger log = LoggerFactory.getLogger(TestWebSocketConnection.class);

    private static final String HOST = "localhost";
    private static final int PORT = TermdServer.getNextPort();
    private static final String TEST_COMMAND = "java -cp ./server/target/test-classes/:./target/test-classes/ org.jboss.pnc.buildagent.MockProcess 4";

    private static File logFolder = Paths.get("").toAbsolutePath().toFile();
    private static File logFile = new File(logFolder, "console.log");

    String terminalBaseUrl = "http://" + HOST + ":" + PORT;
    String listenerBaseUrl = "http://" + HOST + ":" + PORT;

    @BeforeClass
    public static void setUP() throws Exception {
        TermdServer.startServer(HOST, PORT, "");
    }

    @AfterClass
    public static void tearDown() {
        TermdServer.stopServer();
        log.debug("Deleting log file {}", logFile);
        logFile.delete();

    }

    @Test
    public void serverShouldBeUpAndRunning() throws Exception {
        String content = readUrl(HOST, PORT, "/");
        Assert.assertTrue("Cannot read response from serverThread.", content.length() > 0);
    }

    @Test
    public void clientShouldBeAbleToRunRemoteCommandAndReceiveBinaryResults() throws Exception {
        clientShouldBeAbleToRunRemoteCommandAndReceiveResults(ResponseMode.BINARY);
    }

    @Test
    public void clientShouldBeAbleToRunRemoteCommandAndReceiveTextResults() throws Exception {
        clientShouldBeAbleToRunRemoteCommandAndReceiveResults(ResponseMode.BINARY);
    }

    public void clientShouldBeAbleToRunRemoteCommandAndReceiveResults(ResponseMode responseMode) throws Exception {
        String context = this.getClass().getName() + ".clientShouldBeAbleToRunRemoteCommandAndReceiveResults" + responseMode;

        List<TaskStatusUpdateEvent> remoteResponseStatuses = new ArrayList<>();
        Consumer<TaskStatusUpdateEvent> onStatusUpdate = (statusUpdateEvent) -> {
            remoteResponseStatuses.add(statusUpdateEvent);
        };
        List<String> remoteResponses = new ArrayList<>();

        Consumer<String> onResponseData = (responseData) -> {
            log.trace("Adding to remote response list [{}].", responseData);
            remoteResponses.add(responseData);
        };
        BuildAgentClient buildAgentClient = new BuildAgentClient(
                terminalBaseUrl,
                listenerBaseUrl,
                Optional.of(onResponseData),
                onStatusUpdate,
                context,
                Optional.empty(),
                ResponseMode.BINARY);
        buildAgentClient.executeCommand(TEST_COMMAND);

        assertThatResultWasReceived(remoteResponses, 10, ChronoUnit.SECONDS);
        assertThatCommandCompletedSuccessfully(remoteResponseStatuses, 10, ChronoUnit.SECONDS);
        assertThatLogWasWritten(remoteResponseStatuses);

        buildAgentClient.close();
    }

    @Test
    public void shouldExecuteTwoTasksAndWriteToLogs() throws Exception {

        String context = this.getClass().getName() + ".shouldExecuteTwoTasksAndWriteToLogs";

        ObjectWrapper<Boolean> completed = new ObjectWrapper<>(false);
        Consumer<TaskStatusUpdateEvent> onStatusUpdate = (statusUpdateEvent) -> {
            if (statusUpdateEvent.getNewStatus().equals(Status.COMPLETED) ) {
                assertTestCommandOutputIsWrittenToLog(statusUpdateEvent.getTaskId());
                completed.set(true);
            }
        };

        BuildAgentClient buildAgentClient = new BuildAgentClient(terminalBaseUrl, listenerBaseUrl, Optional.empty(), onStatusUpdate, context, Optional.empty());

        buildAgentClient.executeCommand(TEST_COMMAND);
        Wait.forCondition(() -> completed.get(), 10, ChronoUnit.SECONDS, "Client was not connected within given timeout."); //TODO no need to wait, server should block new executions until there are running tasks
        completed.set(false);

        buildAgentClient.executeCommand(TEST_COMMAND);
        Wait.forCondition(() -> completed.get(), 10, ChronoUnit.SECONDS, "Client was not connected within given timeout.");
        completed.set(false);

        buildAgentClient.close();
    }

    @Test
    public void clientShouldBeAbleToConnectToRunningProcess() throws Exception {
        String context = this.getClass().getName() + ".clientShouldBeAbleToConnectToRunningProcess";

        ObjectWrapper<Boolean> completed = new ObjectWrapper<>(false);
        Consumer<TaskStatusUpdateEvent> onStatusUpdate = (statusUpdateEvent) -> {
            if (statusUpdateEvent.getNewStatus().equals(Status.COMPLETED)) {
                completed.set(true);
            }
        };
        BuildAgentClient buildAgentClient = new BuildAgentClient(terminalBaseUrl, listenerBaseUrl, Optional.empty(), onStatusUpdate, context, Optional.empty());
        buildAgentClient.executeCommand(TEST_COMMAND);

        StringBuilder response = new StringBuilder();
        Consumer<String> onResponse = (message) -> {
            response.append(message);
        };
        BuildAgentClient buildAgentClientReconnected = new BuildAgentClient(terminalBaseUrl, listenerBaseUrl, Optional.of(onResponse), (event) -> {}, context, Optional.of("reconnect"));

        Wait.forCondition(() -> completed.get(), 10, ChronoUnit.SECONDS, "Operation did not complete within given timeout.");

        Assert.assertTrue("Missing or invalid response: " + response.toString(), response.toString().contains("I'm done."));

        buildAgentClientReconnected.close();
        buildAgentClient.close();

    }

    private void assertThatResultWasReceived(List<String> strings, long timeout, TemporalUnit timeUnit) throws InterruptedException {
        Supplier<Boolean> evaluationSupplier = () -> {
            StringBuilder remoteResponses = new StringBuilder();
            for (String string : strings) {
                remoteResponses.append(string);
            }

            log.trace("Remote responses: {}.", remoteResponses);
            return remoteResponses.toString().contains(MockProcess.WELCOME_MESSAGE);
        };

        try {
            Wait.forCondition(evaluationSupplier, timeout, timeUnit, "Client did not receive welcome message within given timeout.");
        } catch (TimeoutException e) {
            throw new AssertionError("Response should contain message " + MockProcess.WELCOME_MESSAGE + ".", e);
        }
    }

    private void assertThatCommandCompletedSuccessfully(List<TaskStatusUpdateEvent> remoteResponseStatuses, long timeout, TemporalUnit timeUnit) throws InterruptedException {
        Supplier<Boolean> checkForResponses = () -> {
            List<TaskStatusUpdateEvent> receivedStatuses = remoteResponseStatuses;
            List<Status> collectedUpdates = receivedStatuses.stream().map(event -> event.getNewStatus()).collect(Collectors.toList());
            return collectedUpdates.contains(Status.RUNNING) && collectedUpdates.contains(Status.COMPLETED);
        };

        try {
            Wait.forCondition(checkForResponses, timeout, timeUnit, "Client was not connected within given timeout.");
        } catch (TimeoutException e) {
            throw new AssertionError("Response should contain status Status.RUNNING and Status.COMPLETED.", e);
        }
    }

    private void assertThatLogWasWritten(List<TaskStatusUpdateEvent> remoteResponseStatuses) throws IOException, TimeoutException, InterruptedException {
        List<TaskStatusUpdateEvent> responses = remoteResponseStatuses;
        Optional<TaskStatusUpdateEvent> firstResponse = responses.stream().findFirst();
        if (!firstResponse.isPresent()) {
            throw new AssertionError("There is no status update event to retrieve task id.");
        }

        TaskStatusUpdateEvent taskStatusUpdateEvent = firstResponse.get();
        String taskId = taskStatusUpdateEvent.getTaskId() + "";

        Supplier<Boolean> completedStatusReceived = () -> {
            for (TaskStatusUpdateEvent event : responses) {
                if (event.getNewStatus().equals(Status.COMPLETED)) {
                    log.debug("Found received completed status for task {}", event.getTaskId());
                    return true;
                }
            }
            return false;
        };
        Wait.forCondition(completedStatusReceived, 10, ChronoUnit.SECONDS, "Client was not connected within given timeout.");

        assertTestCommandOutputIsWrittenToLog(taskId);
    }

    private void assertTestCommandOutputIsWrittenToLog(String taskId) {
        Assert.assertTrue("Missing log file: " + logFile, logFile.exists());

        String fileContent;
        try {
            fileContent = new String(Files.readAllBytes(logFile.toPath()));
        } catch (IOException e) {
            throw new AssertionError("Cannot read log file.", e);
        }
        log.debug("Log file content: [{}].", fileContent);
        Assert.assertTrue("Missing executed command in log file of task " + taskId + ".", fileContent.contains(TEST_COMMAND));
        Assert.assertTrue("Missing response message in log file of task " + taskId + ".", fileContent.contains("Hello again"));
        Assert.assertTrue("Missing final line in the log file of task " + taskId + ".", fileContent.contains("I'm done."));
        Assert.assertTrue("Missing or invalid completion state of task " + taskId + ".", fileContent.contains("# Finished with status: " + Status.COMPLETED.toString()));
    }

    private String readUrl(String host, int port, String path) throws IOException {
        URL url = new URL("http://" + host + ":" + port + path);
        URLConnection connection = url.openConnection();
        connection.connect();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

        String inputLine;
        StringBuilder stringBuilder = new StringBuilder();
        while ((inputLine = bufferedReader.readLine()) != null) {
            stringBuilder.append(inputLine);
        }
        bufferedReader.close();
        return stringBuilder.toString();
    }

}
