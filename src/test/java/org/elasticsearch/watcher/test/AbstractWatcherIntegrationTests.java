/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.test;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.license.plugin.LicensePlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.esusers.ESUsersRealm;
import org.elasticsearch.shield.crypto.InternalCryptoService;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.TestCluster;
import org.elasticsearch.watcher.WatcherLifeCycleService;
import org.elasticsearch.watcher.WatcherPlugin;
import org.elasticsearch.watcher.WatcherService;
import org.elasticsearch.watcher.WatcherState;
import org.elasticsearch.watcher.actions.email.service.Authentication;
import org.elasticsearch.watcher.actions.email.service.Email;
import org.elasticsearch.watcher.actions.email.service.EmailService;
import org.elasticsearch.watcher.actions.email.service.Profile;
import org.elasticsearch.watcher.client.WatcherClient;
import org.elasticsearch.watcher.execution.ExecutionService;
import org.elasticsearch.watcher.execution.ExecutionState;
import org.elasticsearch.watcher.history.HistoryStore;
import org.elasticsearch.watcher.execution.TriggeredWatchStore;
import org.elasticsearch.watcher.license.LicenseService;
import org.elasticsearch.watcher.support.clock.ClockMock;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.xcontent.XContentSource;
import org.elasticsearch.watcher.trigger.ScheduleTriggerEngineMock;
import org.elasticsearch.watcher.trigger.TriggerService;
import org.elasticsearch.watcher.trigger.schedule.ScheduleModule;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchStore;
import org.hamcrest.Matcher;
import org.jboss.netty.util.internal.SystemPropertyUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

/**
 */
@ClusterScope(scope = SUITE, numClientNodes = 0, transportClientRatio = 0, randomDynamicTemplates = false, maxNumDataNodes = 3)
public abstract class AbstractWatcherIntegrationTests extends ElasticsearchIntegrationTest {

    private static final boolean timeWarpEnabled = SystemPropertyUtil.getBoolean("tests.timewarp", true);

    private TimeWarp timeWarp;

    private static Boolean shieldEnabled;

    private static ScheduleModule.Engine scheduleEngine;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        String scheduleImplName = scheduleEngine().name().toLowerCase(Locale.ROOT);
        logger.info("using schedule engine [" + scheduleImplName + "]");
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                // we do this by default in core, but for watcher this isn't needed and only adds noise.
                .put("index.store.mock.check_index_on_close", false)
                .put("scroll.size", randomIntBetween(1, 100))
                .put("plugin.types", Strings.collectionToCommaDelimitedString(pluginTypes()))
                .put(ShieldSettings.settings(shieldEnabled))
                .put("watcher.trigger.schedule.engine", scheduleImplName)
                .build();
    }

    protected List<String> pluginTypes() {
        List<String> types = new ArrayList<>();
        if (timeWarped()) {
            types.add(TimeWarpedWatcherPlugin.class.getName());
        } else {
            types.add(WatcherPlugin.class.getName());
        }
        if (shieldEnabled) {
            types.add(ShieldPlugin.class.getName());
        }
        types.add(licensePluginClass().getName());
        return types;
    }

    /**
     * @return  whether the test suite should run in time warp mode. By default this will be determined globally
     *          to all test suites based on {@code -Dtests.timewarp} system property (when missing, defaults to
     *          {@code true}). If a test suite requires to force the mode or force not running under this mode
     *          this method can be overridden.
     */
    protected boolean timeWarped() {
        return timeWarpEnabled;
    }

    /**
     * @return whether shield has been enabled
     */
    protected final boolean shieldEnabled() {
        return shieldEnabled;
    }

    /**
     * @return The schedule trigger engine that will be used for the nodes.
     */
    protected final ScheduleModule.Engine scheduleEngine() {
        return scheduleEngine;
    }

    /**
     * Override and returns {@code false} to force running without shield
     */
    protected boolean enableShield() {
        return randomBoolean();
    }

    protected Class<? extends Plugin> licensePluginClass() {
        return LicensePlugin.class;
    }

    protected boolean checkWatcherRunningOnlyOnce() {
        return true;
    }

    @Before
    public void _setup() throws Exception {
        setupTimeWarp();
        startWatcherIfNodesExist();
    }

    @After
    public void _cleanup() throws Exception {
        // Clear all internal watcher state for the next test method:
        logger.info("[{}#{}]: clearing watcher state", getTestClass().getSimpleName(), getTestName());
        if (checkWatcherRunningOnlyOnce()) {
            ensureWatcherOnlyRunningOnce();
        }
        stopWatcher(false);
    }

    @AfterClass
    public static void _cleanupClass() {
        shieldEnabled = null;
        scheduleEngine = null;
    }

    @Override
    protected Settings transportClientSettings() {
        if (!shieldEnabled) {
            return Settings.builder()
                    .put(super.transportClientSettings())
                    .put("plugin.types", WatcherPlugin.class.getName())
                    .build();
        }

        return Settings.builder()
                .put("client.transport.sniff", false)
                .put("plugin.types", ShieldPlugin.class.getName() + "," + WatcherPlugin.class.getName())
                .put("shield.user", "admin:changeme")
                .build();
    }

    private void setupTimeWarp() throws Exception {
        if (timeWarped()) {
            timeWarp = new TimeWarp(getInstanceFromMaster(ScheduleTriggerEngineMock.class), getInstanceFromMaster(ClockMock.class));
        }
    }

    private void startWatcherIfNodesExist() throws Exception {
        if (internalTestCluster().size() > 0) {
            ensureLicenseEnabled();
            WatcherState state = getInstanceFromMaster(WatcherService.class).state();
            if (state == WatcherState.STOPPED) {
                logger.info("[{}#{}]: starting watcher", getTestClass().getSimpleName(), getTestName());
                startWatcher(false);
            } else if (state == WatcherState.STARTING) {
                logger.info("[{}#{}]: watcher is starting, waiting for it to get in a started state", getTestClass().getSimpleName(), getTestName());
                ensureWatcherStarted(false);
            } else {
                logger.info("[{}#{}]: not starting watcher, because watcher is in state [{}]", getTestClass().getSimpleName(), getTestName(), state);
            }
        } else {
            logger.info("[{}#{}]: not starting watcher, because test cluster has no nodes", getTestClass().getSimpleName(), getTestName());
        }
    }

    protected TimeWarp timeWarp() {
        assert timeWarped() : "cannot access TimeWarp when test context is not time warped";
        return timeWarp;
    }

    public boolean randomizeNumberOfShardsAndReplicas() {
        return false;
    }

    @Override
    protected TestCluster buildTestCluster(Scope scope, long seed) throws IOException {
        // This overwrites the wipe logic of the test cluster to not remove the watches and watch_history templates. By default all templates are removed
        // TODO: We should have the notion of a hidden template (like hidden index / type) that only gets removed when specifically mentioned.
        final TestCluster testCluster = super.buildTestCluster(scope, seed);
        return new WatcherWrappingCluster(seed, testCluster);
    }

    protected long docCount(String index, String type, QueryBuilder query) {
        refresh();
        return docCount(index, type, SearchSourceBuilder.searchSource().query(query));
    }

    protected long watchRecordCount(QueryBuilder query) {
        refresh();
        return docCount(HistoryStore.INDEX_PREFIX + "*", HistoryStore.DOC_TYPE, SearchSourceBuilder.searchSource().query(query));
    }

    protected long docCount(String index, String type, SearchSourceBuilder source) {
        SearchRequestBuilder builder = client().prepareSearch(index).setSearchType(SearchType.COUNT);
        if (type != null) {
            builder.setTypes(type);
        }
        builder.setSource(source.buildAsBytes());
        return builder.get().getHits().getTotalHits();
    }

    protected <T> T getInstanceFromMaster(Class<T> type) {
        return internalTestCluster().getInstance(type, internalTestCluster().getMasterName());
    }

    protected Watch.Parser watchParser() {
        return getInstanceFromMaster(Watch.Parser.class);
    }

    protected ExecutionService executionService() {
        return getInstanceFromMaster(ExecutionService.class);
    }

    protected WatcherService watchService() {
        return getInstanceFromMaster(WatcherService.class);
    }

    protected TriggerService triggerService() {
        return getInstanceFromMaster(TriggerService.class);
    }

    public AbstractWatcherIntegrationTests() {
        super();
    }

    protected WatcherClient watcherClient() {
        return shieldEnabled ?
                new WatcherClient(internalTestCluster().transportClient()) :
                new WatcherClient(client());
    }

    protected ScriptServiceProxy scriptService() {
        return internalTestCluster().getInstance(ScriptServiceProxy.class);
    }

    protected HttpClient watcherHttpClient() {
        return internalTestCluster().getInstance(HttpClient.class);
    }

    protected EmailService noopEmailService() {
        return new NoopEmailService();
    }

    protected LicenseService licenseService() {
        return getInstanceFromMaster(LicenseService.class);
    }

    protected void assertValue(XContentSource source, String path, Matcher<?> matcher) {
        assertThat(source.getValue(path), (Matcher<Object>) matcher);
    }

    protected void assertWatchWithMinimumPerformedActionsCount(final String watchName, final long minimumExpectedWatchActionsWithActionPerformed) throws Exception {
        assertWatchWithMinimumPerformedActionsCount(watchName, minimumExpectedWatchActionsWithActionPerformed, true);
    }

    protected void assertWatchWithMinimumPerformedActionsCount(final String watchName, final long minimumExpectedWatchActionsWithActionPerformed, final boolean assertConditionMet) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                String[] watchHistoryIndices = state.metaData().concreteIndices(IndicesOptions.lenientExpandOpen(), HistoryStore.INDEX_PREFIX + "*");
                assertThat(watchHistoryIndices, not(emptyArray()));
                for (String index : watchHistoryIndices) {
                    IndexRoutingTable routingTable = state.getRoutingTable().index(index);
                    assertThat(routingTable, notNullValue());
                    assertThat(routingTable.allPrimaryShardsActive(), is(true));
                }

                refresh();
                SearchResponse searchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*")
                        .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                        .setQuery(boolQuery().must(matchQuery("watch_id", watchName)).must(matchQuery("state", ExecutionState.EXECUTED.id())))
                        .get();
                assertThat("could not find executed watch record", searchResponse.getHits().getTotalHits(), greaterThanOrEqualTo(minimumExpectedWatchActionsWithActionPerformed));
                if (assertConditionMet) {
                    assertThat((Integer) XContentMapValues.extractValue("result.input.payload.hits.total", searchResponse.getHits().getAt(0).sourceAsMap()), greaterThanOrEqualTo(1));
                }
            }
        });
    }

    protected long historyRecordsCount(String watchName) {
        refresh();
        SearchResponse searchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*")
                .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                .setSearchType(SearchType.COUNT)
                .setQuery(matchQuery("watch_id", watchName))
                .get();
        return searchResponse.getHits().getTotalHits();
    }

    protected long findNumberOfPerformedActions(String watchName) {
        refresh();
        SearchResponse searchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*")
                .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                .setQuery(boolQuery().must(matchQuery("watch_id", watchName)).must(matchQuery("state", ExecutionState.EXECUTED.id())))
                .get();
        return searchResponse.getHits().getTotalHits();
    }

    protected void assertWatchWithNoActionNeeded(final String watchName, final long expectedWatchActionsWithNoActionNeeded) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                // The watch_history index gets created in the background when the first watch is triggered, so we to check first is this index is created and shards are started
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                String[] watchHistoryIndices = state.metaData().concreteIndices(IndicesOptions.lenientExpandOpen(), HistoryStore.INDEX_PREFIX + "*");
                assertThat(watchHistoryIndices, not(emptyArray()));
                for (String index : watchHistoryIndices) {
                    IndexRoutingTable routingTable = state.getRoutingTable().index(index);
                    assertThat(routingTable, notNullValue());
                    assertThat(routingTable.allPrimaryShardsActive(), is(true));
                }

                refresh();
                SearchResponse searchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*")
                        .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                        .setQuery(boolQuery().must(matchQuery("watch_id", watchName)).must(matchQuery("state", ExecutionState.EXECUTION_NOT_NEEDED.id())))
                        .get();
                assertThat(searchResponse.getHits().getTotalHits(), greaterThanOrEqualTo(expectedWatchActionsWithNoActionNeeded));
            }
        });
    }

    protected void assertWatchWithMinimumActionsCount(final String watchName, final ExecutionState recordState, final long recordCount) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                String[] watchHistoryIndices = state.metaData().concreteIndices(IndicesOptions.lenientExpandOpen(), HistoryStore.INDEX_PREFIX + "*");
                assertThat(watchHistoryIndices, not(emptyArray()));
                for (String index : watchHistoryIndices) {
                    IndexRoutingTable routingTable = state.getRoutingTable().index(index);
                    assertThat(routingTable, notNullValue());
                    assertThat(routingTable.allPrimaryShardsActive(), is(true));
                }

                refresh();
                SearchResponse searchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*")
                        .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                        .setQuery(boolQuery().must(matchQuery("watch_id", watchName)).must(matchQuery("state", recordState.id())))
                        .get();
                assertThat("could not find executed watch record", searchResponse.getHits().getTotalHits(), greaterThanOrEqualTo(recordCount));
            }
        });
    }

    protected void ensureWatcherStarted() throws Exception {
        ensureWatcherStarted(true);
    }

    protected void ensureWatcherStarted(final boolean useClient) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                if (useClient) {
                    assertThat(watcherClient().prepareWatcherStats().get().getWatcherState(), is(WatcherState.STARTED));
                } else {
                    assertThat(getInstanceFromMaster(WatcherService.class).state(), is(WatcherState.STARTED));
                }
            }
        });
    }

    protected void ensureLicenseEnabled()  throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                for (LicenseService service : internalTestCluster().getInstances(LicenseService.class)) {
                    assertThat(service.enabled(), is(true));
                }
            }
        });
    }

    protected void ensureWatcherStopped() throws Exception {
        ensureWatcherStopped(true);
    }

    protected void ensureWatcherStopped(final boolean useClient) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                if (useClient) {
                    assertThat(watcherClient().prepareWatcherStats().get().getWatcherState(), is(WatcherState.STOPPED));
                } else {
                    assertThat(getInstanceFromMaster(WatcherService.class).state(), is(WatcherState.STOPPED));
                }
            }
        });
    }

    protected void startWatcher() throws Exception {
        startWatcher(true);
    }

    protected void stopWatcher() throws Exception {
        stopWatcher(true);
    }

    protected void startWatcher(boolean useClient) throws Exception {
        if (useClient) {
            watcherClient().prepareWatchService().start().get();
        } else {
            getInstanceFromMaster(WatcherLifeCycleService.class).start();
        }
        ensureWatcherStarted(useClient);
    }

    protected void stopWatcher(boolean useClient) throws Exception {
        if (useClient) {
            watcherClient().prepareWatchService().stop().get();
        } else {
            getInstanceFromMaster(WatcherLifeCycleService.class).stop();
        }
        ensureWatcherStopped(useClient);
    }

    protected void ensureWatcherOnlyRunningOnce() {
        int running = 0;
        for (WatcherService watcherService : internalTestCluster().getInstances(WatcherService.class)) {
            if (watcherService.state() == WatcherState.STARTED) {
                running++;
            }
        }
        assertThat("watcher should only run on the elected master node, but it is running on [" + running + "] nodes", running, equalTo(1));
    }

    protected static InternalTestCluster internalTestCluster() {
        return (InternalTestCluster) ((WatcherWrappingCluster) cluster()).testCluster;
    }

    // We need this custom impl, because we have custom wipe logic. We don't want the watcher index templates to get deleted between tests
    private final class WatcherWrappingCluster extends TestCluster {

        private final TestCluster testCluster;

        private WatcherWrappingCluster(long seed, TestCluster testCluster) {
            super(seed);
            this.testCluster = testCluster;
        }

        @Override
        public void beforeTest(Random random, double transportClientRatio) throws IOException {
            if (scheduleEngine == null) {
                scheduleEngine = randomFrom(ScheduleModule.Engine.values());
            }
            if (shieldEnabled == null) {
                shieldEnabled = enableShield();
            }
            testCluster.beforeTest(random, transportClientRatio);
        }

        @Override
        public void wipe() {
            wipeIndices("_all");
            wipeRepositories();

            if (size() > 0) {
                List<String> templatesToWipe = new ArrayList<>();
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                for (ObjectObjectCursor<String, IndexTemplateMetaData> cursor : state.getMetaData().templates()) {
                    if (cursor.key.equals(WatchStore.INDEX_TEMPLATE) || cursor.key.equals(HistoryStore.INDEX_TEMPLATE_NAME) || cursor.key.equals(TriggeredWatchStore.INDEX_TEMPLATE_NAME)) {
                        continue;
                    }
                    templatesToWipe.add(cursor.key);
                }
                if (!templatesToWipe.isEmpty()) {
                    wipeTemplates(templatesToWipe.toArray(new String[templatesToWipe.size()]));
                }
            }
        }

        @Override
        public void afterTest() throws IOException {
            testCluster.afterTest();
        }

        @Override
        public Client client() {
            return testCluster.client();
        }

        @Override
        public int size() {
            return testCluster.size();
        }

        @Override
        public int numDataNodes() {
            return testCluster.numDataNodes();
        }

        @Override
        public int numDataAndMasterNodes() {
            return testCluster.numDataAndMasterNodes();
        }

        @Override
        public InetSocketAddress[] httpAddresses() {
            return testCluster.httpAddresses();
        }

        @Override
        public void close() throws IOException {
            testCluster.close();
        }

        @Override
        public void ensureEstimatedStats() {
            testCluster.ensureEstimatedStats();
        }

        @Override
        public String getClusterName() {
            return testCluster.getClusterName();
        }

        @Override
        public Iterator<Client> iterator() {
            return testCluster.iterator();
        }

    }

    private static class NoopEmailService implements EmailService {

        @Override
        public EmailSent send(Email email, Authentication auth, Profile profile) {
            return new EmailSent(auth.user(), email);
        }

        @Override
        public EmailSent send(Email email, Authentication auth, Profile profile, String accountName) {
            return new EmailSent(accountName, email);
        }
    }

    protected static class TimeWarp {

        protected final ScheduleTriggerEngineMock scheduler;
        protected final ClockMock clock;

        public TimeWarp(ScheduleTriggerEngineMock scheduler, ClockMock clock) {
            this.scheduler = scheduler;
            this.clock = clock;
        }

        public ScheduleTriggerEngineMock scheduler() {
            return scheduler;
        }

        public ClockMock clock() {
            return clock;
        }
    }


    /** Shield related settings */

    public static class ShieldSettings {

        public static final String TEST_USERNAME = "test";
        public static final String TEST_PASSWORD = "changeme";

        static boolean auditLogsEnabled = SystemPropertyUtil.getBoolean("tests.audit_logs", true);
        static byte[] systemKey = generateKey(); // must be the same for all nodes

        public static final String IP_FILTER = "allow: all\n";

        public static final String USERS =
                "transport_client:{plain}changeme\n" +
                TEST_USERNAME + ":{plain}" + TEST_PASSWORD + "\n" +
                "admin:{plain}changeme\n" +
                "monitor:{plain}changeme";

        public static final String USER_ROLES =
                "transport_client:transport_client\n" +
                "test:test\n" +
                "admin:admin\n" +
                "monitor:monitor";

        public static final String ROLES =
                "test:\n" + // a user for the test infra.
                "  cluster: cluster:monitor/nodes/info, cluster:monitor/state, cluster:monitor/health, cluster:monitor/stats, cluster:admin/settings/update, cluster:admin/repository/delete, indices:admin/template/get, indices:admin/template/put, indices:admin/template/delete\n" +
                "  indices:\n" +
                "    '*': all\n" +
                "\n" +
                "admin:\n" +
                "  cluster: manage_watcher, cluster:monitor/nodes/info\n" +
                "transport_client:\n" +
                "  cluster: cluster:monitor/nodes/info\n" +
                "\n" +
                "monitor:\n" +
                "  cluster: monitor_watcher, cluster:monitor/nodes/info\n"
                ;


        public static Settings settings(boolean enabled)  {
            Settings.Builder builder = Settings.builder();
            if (!enabled) {
                return builder.put("shield.enabled", false).build();
            }
            try {
                Path folder = createTempDir().resolve("watcher_shield");
                Files.createDirectories(folder);
                return builder.put("shield.enabled", true)
                        .put("shield.user", "test:changeme")
                        .put("shield.authc.realms.esusers.type", ESUsersRealm.TYPE)
                        .put("shield.authc.realms.esusers.order", 0)
                        .put("shield.authc.realms.esusers.files.users", writeFile(folder, "users", USERS))
                        .put("shield.authc.realms.esusers.files.users_roles", writeFile(folder, "users_roles", USER_ROLES))
                        .put("shield.authz.store.files.roles", writeFile(folder, "roles.yml", ROLES))
                        .put("shield.transport.n2n.ip_filter.file", writeFile(folder, "ip_filter.yml", IP_FILTER))
                        .put("shield.system_key.file", writeFile(folder, "system_key.yml", systemKey))
                        .put("shield.authc.sign_user_header", false)
                        .put("shield.audit.enabled", auditLogsEnabled)
                        .build();
            } catch (IOException ex) {
                throw new RuntimeException("failed to build settings for shield", ex);
            }
        }

        static byte[] generateKey() {
            try {
                return InternalCryptoService.generateKey();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static String writeFile(Path folder, String name, String content) throws IOException {
            Path file = folder.resolve(name);
            try (BufferedWriter stream = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                Streams.copy(content, stream);
            } catch (IOException e) {
                throw new ElasticsearchException("error writing file in test", e);
            }
            return file.toAbsolutePath().toString();
        }

        public static String writeFile(Path folder, String name, byte[] content) throws IOException {
            Path file = folder.resolve(name);
            try (OutputStream stream = Files.newOutputStream(file)) {
                Streams.copy(content, stream);
            } catch (IOException e) {
                throw new ElasticsearchException("error writing file in test", e);
            }
            return file.toAbsolutePath().toString();
        }
    }

}
