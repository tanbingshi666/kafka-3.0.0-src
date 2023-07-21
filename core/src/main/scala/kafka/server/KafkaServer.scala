/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import java.io.{File, IOException}
import java.net.{InetAddress, SocketTimeoutException}
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import kafka.api.{KAFKA_0_9_0, KAFKA_2_2_IV0, KAFKA_2_4_IV1}
import kafka.cluster.{Broker, EndPoint}
import kafka.common.{GenerateBrokerIdException, InconsistentBrokerIdException, InconsistentClusterIdException}
import kafka.controller.KafkaController
import kafka.coordinator.group.GroupCoordinator
import kafka.coordinator.transaction.{ProducerIdManager, TransactionCoordinator}
import kafka.log.LogManager
import kafka.metrics.{KafkaMetricsReporter, KafkaYammerMetrics}
import kafka.network.SocketServer
import kafka.security.CredentialProvider
import kafka.server.metadata.ZkConfigRepository
import kafka.utils._
import kafka.zk.{AdminZkClient, BrokerInfo, KafkaZkClient}
import org.apache.kafka.clients.{ApiVersions, ManualMetadataUpdater, NetworkClient, NetworkClientUtils}
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.message.ApiMessageType.ListenerType
import org.apache.kafka.common.message.ControlledShutdownRequestData
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network._
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{ControlledShutdownRequest, ControlledShutdownResponse}
import org.apache.kafka.common.security.scram.internals.ScramMechanism
import org.apache.kafka.common.security.token.delegation.internals.DelegationTokenCache
import org.apache.kafka.common.security.{JaasContext, JaasUtils}
import org.apache.kafka.common.utils.{AppInfoParser, LogContext, Time, Utils}
import org.apache.kafka.common.{Endpoint, Node}
import org.apache.kafka.metadata.BrokerState
import org.apache.kafka.server.authorizer.Authorizer
import org.apache.zookeeper.client.ZKClientConfig

import scala.collection.{Map, Seq}
import scala.jdk.CollectionConverters._

object KafkaServer {

  def zkClientConfigFromKafkaConfig(config: KafkaConfig, forceZkSslClientEnable: Boolean = false): ZKClientConfig = {
    val clientConfig = new ZKClientConfig
    if (config.zkSslClientEnable || forceZkSslClientEnable) {
      KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslClientEnableProp, "true")
      config.zkClientCnxnSocketClassName.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkClientCnxnSocketProp, _))
      config.zkSslKeyStoreLocation.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslKeyStoreLocationProp, _))
      config.zkSslKeyStorePassword.foreach(x => KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslKeyStorePasswordProp, x.value))
      config.zkSslKeyStoreType.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslKeyStoreTypeProp, _))
      config.zkSslTrustStoreLocation.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslTrustStoreLocationProp, _))
      config.zkSslTrustStorePassword.foreach(x => KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslTrustStorePasswordProp, x.value))
      config.zkSslTrustStoreType.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslTrustStoreTypeProp, _))
      KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslProtocolProp, config.ZkSslProtocol)
      config.ZkSslEnabledProtocols.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslEnabledProtocolsProp, _))
      config.ZkSslCipherSuites.foreach(KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslCipherSuitesProp, _))
      KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslEndpointIdentificationAlgorithmProp, config.ZkSslEndpointIdentificationAlgorithm)
      KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslCrlEnableProp, config.ZkSslCrlEnable.toString)
      KafkaConfig.setZooKeeperClientProperty(clientConfig, KafkaConfig.ZkSslOcspEnableProp, config.ZkSslOcspEnable.toString)
    }
    clientConfig
  }

  val MIN_INCREMENTAL_FETCH_SESSION_EVICTION_MS: Long = 120000
}

/**
 * Represents the lifecycle of a single Kafka broker. Handles all functionality required
 * to start up and shutdown a single Kafka node.
 */
class KafkaServer(
                   // server.properties 文件内容
                   val config: KafkaConfig,
                   time: Time = Time.SYSTEM,
                   threadNamePrefix: Option[String] = None,
                   enableForwarding: Boolean = false
                 ) extends KafkaBroker with Server {

  private val startupComplete = new AtomicBoolean(false)
  private val isShuttingDown = new AtomicBoolean(false)
  private val isStartingUp = new AtomicBoolean(false)

  @volatile private var _brokerState: BrokerState = BrokerState.NOT_RUNNING
  private var shutdownLatch = new CountDownLatch(1)
  private var logContext: LogContext = null

  private val kafkaMetricsReporters: Seq[KafkaMetricsReporter] =
    KafkaMetricsReporter.startReporters(VerifiableProperties(config.originals))
  var kafkaYammerMetrics: KafkaYammerMetrics = null
  var metrics: Metrics = null

  var dataPlaneRequestProcessor: KafkaApis = null
  var controlPlaneRequestProcessor: KafkaApis = null

  var authorizer: Option[Authorizer] = None
  var socketServer: SocketServer = null
  var dataPlaneRequestHandlerPool: KafkaRequestHandlerPool = null
  var controlPlaneRequestHandlerPool: KafkaRequestHandlerPool = null

  var logDirFailureChannel: LogDirFailureChannel = null
  var logManager: LogManager = null

  @volatile private[this] var _replicaManager: ReplicaManager = null
  var adminManager: ZkAdminManager = null
  var tokenManager: DelegationTokenManager = null

  var dynamicConfigHandlers: Map[String, ConfigHandler] = null
  var dynamicConfigManager: DynamicConfigManager = null
  var credentialProvider: CredentialProvider = null
  var tokenCache: DelegationTokenCache = null

  var groupCoordinator: GroupCoordinator = null

  var transactionCoordinator: TransactionCoordinator = null

  var kafkaController: KafkaController = null

  var forwardingManager: Option[ForwardingManager] = None

  var autoTopicCreationManager: AutoTopicCreationManager = null

  var clientToControllerChannelManager: BrokerToControllerChannelManager = null

  var alterIsrManager: AlterIsrManager = null

  var kafkaScheduler: KafkaScheduler = null

  var metadataCache: ZkMetadataCache = null
  var quotaManagers: QuotaFactory.QuotaManagers = null

  // 创建Zookeeper连接配置信息ZKClientConfig
  val zkClientConfig: ZKClientConfig = KafkaServer.zkClientConfigFromKafkaConfig(config)
  private var _zkClient: KafkaZkClient = null
  private var configRepository: ZkConfigRepository = null

  val correlationId: AtomicInteger = new AtomicInteger(0)
  val brokerMetaPropsFile = "meta.properties"
  val brokerMetadataCheckpoints = config.logDirs.map { logDir =>
    (logDir, new BrokerMetadataCheckpoint(new File(logDir + File.separator + brokerMetaPropsFile)))
  }.toMap

  private var _clusterId: String = null
  private var _brokerTopicStats: BrokerTopicStats = null

  private var _featureChangeListener: FinalizedFeatureChangeListener = null

  val brokerFeatures: BrokerFeatures = BrokerFeatures.createDefault()
  val featureCache: FinalizedFeatureCache = new FinalizedFeatureCache(brokerFeatures)

  override def brokerState: BrokerState = _brokerState

  def clusterId: String = _clusterId

  // Visible for testing
  private[kafka] def zkClient = _zkClient

  private[kafka] def brokerTopicStats = _brokerTopicStats

  private[kafka] def featureChangeListener = _featureChangeListener

  def replicaManager: ReplicaManager = _replicaManager

  /**
   * Start up API for bringing up a single instance of the Kafka server.
   * Instantiates the LogManager, the SocketServer and the request handlers - KafkaRequestHandlers
   */
  override def startup(): Unit = {
    try {
      info("starting")

      if (isShuttingDown.get)
        throw new IllegalStateException("Kafka server is still shutting down, cannot re-start!")

      if (startupComplete.get)
        return

      val canStartup = isStartingUp.compareAndSet(false, true)
      if (canStartup) {
        // kafka broker 状态为 STARTING
        _brokerState = BrokerState.STARTING

        /* setup zookeeper */
        // 1 初始化 Zookeeper 客户端
        // 1.1 创建 Zookeeper 客户端 KafkaZkClient
        // 1.2 在 zk 创建持久化 znodes
        initZkClient(time)

        // 2 创建 ZkConfigRepository (类似于配置中心)
        configRepository = new ZkConfigRepository(
          // 创建 AdminZkClient (与 ZK 交互)
          // 比如在 zk 创建 topic、broker元数据等等
          new AdminZkClient(zkClient)
        )

        /* initialize features */
        _featureChangeListener = new FinalizedFeatureChangeListener(featureCache, _zkClient)
        if (config.isFeatureVersioningSupported) {
          _featureChangeListener.initOrThrow(config.zkConnectionTimeoutMs)
        }

        /* Get or create cluster_id */
        // 3 创建或者从 zk 获取集群 ID
        _clusterId = getOrGenerateClusterId(zkClient)
        info(s"Cluster ID = ${clusterId}")

        /* load metadata */
        // 4 加载磁盘元数据 核心加载 meta.properties 获取集群信息 (比如 clusterId、version、etc)
        val (preloadedBrokerMetadataCheckpoint, initialOfflineDirs) =
        BrokerMetadataCheckpoint.getBrokerMetadataAndOfflineDirs(
          // 从配置文件获取 key = log.dirs 对应的值 也即存储数据目录
          config.logDirs, ignoreMissing = true)

        if (preloadedBrokerMetadataCheckpoint.version != 0) {
          throw new RuntimeException(s"Found unexpected version in loaded `meta.properties`: " +
            s"$preloadedBrokerMetadataCheckpoint. Zk-based brokers only support version 0 " +
            "(which is implicit when the `version` field is missing).")
        }

        /* check cluster id */
        // 4.1 检查集群 id 也即比较 zk 的集群 id 是否和 meta.properties 文件的 clusterId 的值是否一样
        if (preloadedBrokerMetadataCheckpoint.clusterId.isDefined && preloadedBrokerMetadataCheckpoint.clusterId.get != clusterId)
          throw new InconsistentClusterIdException(
            s"The Cluster ID ${clusterId} doesn't match stored clusterId ${preloadedBrokerMetadataCheckpoint.clusterId} in meta.properties. " +
              s"The broker is trying to join the wrong cluster. Configured zookeeper.connect may be wrong.")

        /* generate brokerId */
        // 5 从优先从配置文件 server.properties 文件再从meta.properties 获取 broker.id
        config.brokerId = getOrGenerateBrokerId(preloadedBrokerMetadataCheckpoint)

        // 6 创建 LogContext
        logContext = new LogContext(s"[KafkaServer id=${config.brokerId}] ")
        this.logIdent = logContext.logPrefix

        // initialize dynamic broker configs from ZooKeeper. Any updates made after this will be
        // applied after DynamicConfigManager starts.
        // 7 从 zk 初始化 broker 动态配置
        config.dynamicConfig.initialize(zkClient)

        /* start scheduler */
        // 8 创建 kafka 调度器 KafkaScheduler 并启动(里面默认初始化线程数为 10 的定时调度线程池)
        kafkaScheduler = new KafkaScheduler(config.backgroundThreads)
        kafkaScheduler.startup()

        /* create and configure metrics */
        kafkaYammerMetrics = KafkaYammerMetrics.INSTANCE
        kafkaYammerMetrics.configure(config.originals)
        metrics = Server.initializeMetrics(config, time, clusterId)
        /* register broker metrics */
        _brokerTopicStats = new BrokerTopicStats

        // 9 创建配额管理 QuotaManagers
        quotaManagers = QuotaFactory.instantiate(config, metrics, time, threadNamePrefix.getOrElse(""))
        KafkaBroker.notifyClusterListeners(clusterId, kafkaMetricsReporters ++ metrics.reporters.asScala)

        // 10 创建 LogDirFailureChannel
        logDirFailureChannel = new LogDirFailureChannel(config.logDirs.size)

        /* start log manager */
        // 11.1 创建当前 broker 管理自身 log 数据组件 LogManager
        logManager = LogManager(config, initialOfflineDirs,
          new ZkConfigRepository(new AdminZkClient(zkClient)),
          kafkaScheduler, time, brokerTopicStats, logDirFailureChannel, config.usesTopicId)
        // broker 状态切换
        _brokerState = BrokerState.RECOVERY
        // 11.2 启动 LogManager
        logManager.startup(zkClient.getAllTopicsInCluster())

        // 12 创建 ZkMetadataCache
        metadataCache = MetadataCache.zkMetadataCache(config.brokerId)
        // Enable delegation token cache for all SCRAM mechanisms to simplify dynamic update.
        // This keeps the cache up-to-date if new SCRAM mechanisms are enabled dynamically.
        tokenCache = new DelegationTokenCache(ScramMechanism.mechanismNames)
        credentialProvider = new CredentialProvider(ScramMechanism.mechanismNames, tokenCache)

        // 13.1 创建 broker 与 Controller 交互组件 BrokerToControllerChannelManagerImpl
        clientToControllerChannelManager = BrokerToControllerChannelManager(
          controllerNodeProvider = MetadataCacheControllerNodeProvider(config, metadataCache),
          time = time,
          metrics = metrics,
          config = config,
          channelName = "forwarding",
          threadNamePrefix = threadNamePrefix,
          retryTimeoutMs = config.requestTimeoutMs.longValue)
        // 13.2 启动 BrokerToControllerChannelManagerImpl
        clientToControllerChannelManager.start()

        /* start forwarding manager */
        var autoTopicCreationChannel = Option.empty[BrokerToControllerChannelManager]
        if (enableForwarding) {
          this.forwardingManager = Some(ForwardingManager(clientToControllerChannelManager))
          autoTopicCreationChannel = Some(clientToControllerChannelManager)
        }

        val apiVersionManager = ApiVersionManager(
          ListenerType.ZK_BROKER,
          config,
          forwardingManager,
          brokerFeatures,
          featureCache
        )

        // Create and start the socket server acceptor threads so that the bound port is known.
        // Delay starting processors until the end of the initialization sequence to ensure
        // that credentials have been loaded before processing authentications.
        //
        // Note that we allow the use of KRaft mode controller APIs when forwarding is enabled
        // so that the Envelope request is exposed. This is only used in testing currently.
        // 14.1 创建 SocketServer
        socketServer = new SocketServer(config, metrics, time, credentialProvider, apiVersionManager)
        // 14.2 启动 SocketServer
        socketServer.startup(startProcessingRequests = false)

        /* start replica manager */
        alterIsrManager = if (config.interBrokerProtocolVersion.isAlterIsrSupported) {
          // 15.1 创建 AlterIsrManager 一般用于运维 Topic Partition ISR
          AlterIsrManager(
            config = config,
            metadataCache = metadataCache,
            scheduler = kafkaScheduler,
            time = time,
            metrics = metrics,
            threadNamePrefix = threadNamePrefix,
            brokerEpochSupplier = () => kafkaController.brokerEpoch,
            config.brokerId
          )
        } else {
          AlterIsrManager(kafkaScheduler, time, zkClient)
        }
        // 15.2 启动 AlterIsrManager
        alterIsrManager.start()

        // 16.1 创建管理 topic partition 副本管理组件 ReplicaManager
        _replicaManager = createReplicaManager(isShuttingDown)
        // 16.2 启动 ReplicaManager
        replicaManager.startup()

        // 17.1 创建 broker 信息
        val brokerInfo = createBrokerInfo
        // 17.2 往 zk 写 broker 信息
        val brokerEpoch = zkClient.registerBroker(brokerInfo)

        // Now that the broker is successfully registered, checkpoint its metadata
        // 17.3 checkpoint broker 元数据信息
        checkpointBrokerMetadata(ZkMetaProperties(clusterId, config.brokerId))

        /* start token manager */
        // 18.1 创建 DelegationTokenManager
        tokenManager = new DelegationTokenManager(config, tokenCache, time, zkClient)
        // 18.2 启动 DelegationTokenManager
        tokenManager.startup()

        /* start kafka controller */
        // 19.1 创建 KafkaController
        kafkaController = new KafkaController(config, zkClient, time, metrics, brokerInfo, brokerEpoch, tokenManager, brokerFeatures, featureCache, threadNamePrefix)
        // 19.2 启动 KafkaController
        kafkaController.startup()

        // 20 创建 ZkAdminManager
        adminManager = new ZkAdminManager(config, metrics, metadataCache, zkClient)

        /* start group coordinator */
        // Hardcode Time.SYSTEM for now as some Streams tests fail otherwise, it would be good to fix the underlying issue
        // 21.1 创建消费者组协调器 GroupCoordinator
        groupCoordinator = GroupCoordinator(config, replicaManager, Time.SYSTEM, metrics)
        // 21.2 启动 GroupCoordinator
        groupCoordinator.startup(() => zkClient.getTopicPartitionCount(Topic.GROUP_METADATA_TOPIC_NAME).getOrElse(config.offsetsTopicPartitions))

        /* create producer ids manager */
        val producerIdManager = if (config.interBrokerProtocolVersion.isAllocateProducerIdsSupported) {
          // 22 创建 RPCProducerIdManager
          ProducerIdManager.rpc(
            config.brokerId,
            brokerEpochSupplier = () => kafkaController.brokerEpoch,
            clientToControllerChannelManager,
            config.requestTimeoutMs
          )
        } else {
          ProducerIdManager.zk(config.brokerId, zkClient)
        }

        /* start transaction coordinator, with a separate background thread scheduler for transaction expiration and log loading */
        // Hardcode Time.SYSTEM for now as some Streams tests fail otherwise, it would be good to fix the underlying issue
        // 23.1 创建事务协调器 TransactionCoordinator
        transactionCoordinator = TransactionCoordinator(config, replicaManager, new KafkaScheduler(threads = 1, threadNamePrefix = "transaction-log-manager-"),
          () => producerIdManager, metrics, metadataCache, Time.SYSTEM)
        // 23.2 启动 TransactionCoordinator
        transactionCoordinator.startup(
          () => zkClient.getTopicPartitionCount(Topic.TRANSACTION_STATE_TOPIC_NAME).getOrElse(config.transactionTopicPartitions))

        /* start auto topic creation manager */
        // 24 创建自动主题创建器(比如创建内部主题 __consumer_offsets) DefaultAutoTopicCreationManager
        this.autoTopicCreationManager = AutoTopicCreationManager(
          config,
          metadataCache,
          threadNamePrefix,
          autoTopicCreationChannel,
          Some(adminManager),
          Some(kafkaController),
          groupCoordinator,
          transactionCoordinator
        )

        /* Get the authorizer and initialize it if one is specified.*/
        authorizer = config.authorizer
        authorizer.foreach(_.configure(config.originals))
        val authorizerFutures: Map[Endpoint, CompletableFuture[Void]] = authorizer match {
          case Some(authZ) =>
            authZ.start(brokerInfo.broker.toServerInfo(clusterId, config)).asScala.map { case (ep, cs) =>
              ep -> cs.toCompletableFuture
            }
          case None =>
            brokerInfo.broker.endPoints.map { ep =>
              ep.toJava -> CompletableFuture.completedFuture[Void](null)
            }.toMap
        }

        // 25 创建 FetchManager
        val fetchManager = new FetchManager(Time.SYSTEM,
          new FetchSessionCache(config.maxIncrementalFetchSessionCacheSlots,
            KafkaServer.MIN_INCREMENTAL_FETCH_SESSION_EVICTION_MS))

        /* start processing requests */
        // 26 创建 ZkSupport
        val zkSupport = ZkSupport(adminManager, kafkaController, zkClient, forwardingManager, metadataCache)

        // 27 创建 dataPlane KafkaApis
        dataPlaneRequestProcessor = new KafkaApis(
          // SocketServer 的 RequestChannel
          socketServer.dataPlaneRequestChannel,
          zkSupport, replicaManager, groupCoordinator, transactionCoordinator,
          autoTopicCreationManager, config.brokerId, config,
          configRepository, metadataCache, metrics, authorizer, quotaManagers,
          fetchManager, brokerTopicStats, clusterId, time,
          tokenManager, apiVersionManager)

        // 28 创建 dataPlane 处理线程池 KafkaRequestHandlerPool (里面启动 KafkaRequestHandler 线程组)
        dataPlaneRequestHandlerPool = new KafkaRequestHandlerPool(
          config.brokerId,
          // SocketServer 的 RequestChannel
          socketServer.dataPlaneRequestChannel,
          // KafkaApis
          dataPlaneRequestProcessor,
          time,
          // 默认 8
          config.numIoThreads,
          s"${SocketServer.DataPlaneMetricPrefix}RequestHandlerAvgIdlePercent",
          SocketServer.DataPlaneThreadPrefix)

        // 29 创建 controllerPlane KafkaApis & KafkaRequestHandlerPool
        // 如果配置了 key = control.plane.listener.name 默认 null
        socketServer.controlPlaneRequestChannelOpt.foreach { controlPlaneRequestChannel =>
          controlPlaneRequestProcessor = new KafkaApis(controlPlaneRequestChannel, zkSupport, replicaManager, groupCoordinator, transactionCoordinator,
            autoTopicCreationManager, config.brokerId, config, configRepository, metadataCache, metrics, authorizer, quotaManagers,
            fetchManager, brokerTopicStats, clusterId, time, tokenManager, apiVersionManager)

          controlPlaneRequestHandlerPool = new KafkaRequestHandlerPool(config.brokerId, socketServer.controlPlaneRequestChannelOpt.get, controlPlaneRequestProcessor, time,
            1, s"${SocketServer.ControlPlaneMetricPrefix}RequestHandlerAvgIdlePercent", SocketServer.ControlPlaneThreadPrefix)
        }

        Mx4jLoader.maybeLoad()

        /* Add all reconfigurables for config change notification before starting config handlers */
        config.dynamicConfig.addReconfigurables(this)

        /* start dynamic config manager */
        // 30.1 初始化动态配置 handler
        dynamicConfigHandlers = Map[String, ConfigHandler](
          ConfigType.Topic -> new TopicConfigHandler(logManager, config, quotaManagers, Some(kafkaController)),
          ConfigType.Client -> new ClientIdConfigHandler(quotaManagers),
          ConfigType.User -> new UserConfigHandler(quotaManagers, credentialProvider),
          ConfigType.Broker -> new BrokerConfigHandler(config, quotaManagers),
          ConfigType.Ip -> new IpConfigHandler(socketServer.connectionQuotas))
        // Create the config manager. start listening to notifications
        // 30.2 创建 DynamicConfigManager
        dynamicConfigManager = new DynamicConfigManager(zkClient, dynamicConfigHandlers)
        // 30.3 启动 DynamicConfigManager
        dynamicConfigManager.startup()

        // 31 启动 SocketServer 的 Acceptor 线程和 Processor 线程组
        socketServer.startProcessingRequests(authorizerFutures)

        // 32 broker 状态转换为 RUNNING
        _brokerState = BrokerState.RUNNING
        shutdownLatch = new CountDownLatch(1)
        startupComplete.set(true)
        isStartingUp.set(false)
        AppInfoParser.registerAppInfo(Server.MetricsPrefix, config.brokerId.toString, metrics, time.milliseconds())
        info("started")
      }
    }
    catch {
      case e: Throwable =>
        fatal("Fatal error during KafkaServer startup. Prepare to shutdown", e)
        isStartingUp.set(false)
        shutdown()
        throw e
    }
  }

  protected def createReplicaManager(isShuttingDown: AtomicBoolean): ReplicaManager = {
    new ReplicaManager(config, metrics, time, Some(zkClient), kafkaScheduler, logManager, isShuttingDown, quotaManagers,
      brokerTopicStats, metadataCache, logDirFailureChannel, alterIsrManager)
  }

  private def initZkClient(time: Time): Unit = {
    info(s"Connecting to zookeeper on ${config.zkConnect}")

    val secureAclsEnabled = config.zkEnableSecureAcls
    val isZkSecurityEnabled = JaasUtils.isZkSaslEnabled() || KafkaConfig.zkTlsClientAuthEnabled(zkClientConfig)

    if (secureAclsEnabled && !isZkSecurityEnabled)
      throw new java.lang.SecurityException(s"${KafkaConfig.ZkEnableSecureAclsProp} is true, but ZooKeeper client TLS configuration identifying at least $KafkaConfig.ZkSslClientEnableProp, $KafkaConfig.ZkClientCnxnSocketProp, and $KafkaConfig.ZkSslKeyStoreLocationProp was not present and the " +
        s"verification of the JAAS login file failed ${JaasUtils.zkSecuritySysConfigString}")

    // 1 创建 Zookeeper 客户端 KafkaZkClient
    _zkClient = KafkaZkClient(
      config.zkConnect,
      secureAclsEnabled,
      config.zkSessionTimeoutMs,
      config.zkConnectionTimeoutMs,
      config.zkMaxInFlightRequests,
      time,
      name = "Kafka server",
      zkClientConfig = zkClientConfig,
      createChrootIfNecessary = true)

    // 2 在 zk 创建持久化 znodes
    _zkClient.createTopLevelPaths()
  }

  private def getOrGenerateClusterId(zkClient: KafkaZkClient): String = {
    zkClient.getClusterId.getOrElse(zkClient.createOrGetClusterId(CoreUtils.generateUuidAsBase64()))
  }

  def createBrokerInfo: BrokerInfo = {
    // 1 默认当前 broker 主机和端口信息 比如 hadoop101:9092
    val endPoints = config.advertisedListeners.map(e => s"${e.host}:${e.port}")
    // 2 从 zk 查询 broker.id 对应的信息
    zkClient.getAllBrokersInCluster.filter(_.id != config.brokerId).foreach { broker =>
      val commonEndPoints = broker.endPoints.map(e => s"${e.host}:${e.port}").intersect(endPoints)
      require(commonEndPoints.isEmpty, s"Configured end points ${commonEndPoints.mkString(",")} in" +
        s" advertised listeners are already registered by broker ${broker.id}")
    }

    val listeners = config.advertisedListeners.map { endpoint =>
      if (endpoint.port == 0)
        endpoint.copy(port = socketServer.boundPort(endpoint.listenerName))
      else
        endpoint
    }

    // 3 获取更新 broker 信息
    val updatedEndpoints = listeners.map(endpoint =>
      if (Utils.isBlank(endpoint.host))
        endpoint.copy(host = InetAddress.getLocalHost.getCanonicalHostName)
      else
        endpoint
    )

    val jmxPort = System.getProperty("com.sun.management.jmxremote.port", "-1").toInt
    // 4 创建 broker 信息
    // zk path -> /brokers/ids/{broker.id}
    BrokerInfo(
      Broker(config.brokerId, updatedEndpoints, config.rack, brokerFeatures.supportedFeatures),
      config.interBrokerProtocolVersion,
      jmxPort)
  }

  /**
   * Performs controlled shutdown
   */
  private def controlledShutdown(): Unit = {
    val socketTimeoutMs = config.controllerSocketTimeoutMs

    def doControlledShutdown(retries: Int): Boolean = {
      val metadataUpdater = new ManualMetadataUpdater()
      val networkClient = {
        val channelBuilder = ChannelBuilders.clientChannelBuilder(
          config.interBrokerSecurityProtocol,
          JaasContext.Type.SERVER,
          config,
          config.interBrokerListenerName,
          config.saslMechanismInterBrokerProtocol,
          time,
          config.saslInterBrokerHandshakeRequestEnable,
          logContext)
        val selector = new Selector(
          NetworkReceive.UNLIMITED,
          config.connectionsMaxIdleMs,
          metrics,
          time,
          "kafka-server-controlled-shutdown",
          Map.empty.asJava,
          false,
          channelBuilder,
          logContext
        )
        new NetworkClient(
          selector,
          metadataUpdater,
          config.brokerId.toString,
          1,
          0,
          0,
          Selectable.USE_DEFAULT_BUFFER_SIZE,
          Selectable.USE_DEFAULT_BUFFER_SIZE,
          config.requestTimeoutMs,
          config.connectionSetupTimeoutMs,
          config.connectionSetupTimeoutMaxMs,
          time,
          false,
          new ApiVersions,
          logContext)
      }

      var shutdownSucceeded: Boolean = false

      try {

        var remainingRetries = retries
        var prevController: Node = null
        var ioException = false

        while (!shutdownSucceeded && remainingRetries > 0) {
          remainingRetries = remainingRetries - 1

          // 1. Find the controller and establish a connection to it.
          // If the controller id or the broker registration are missing, we sleep and retry (if there are remaining retries)
          metadataCache.getControllerId match {
            case Some(controllerId) =>
              metadataCache.getAliveBrokerNode(controllerId, config.interBrokerListenerName) match {
                case Some(broker) =>
                  // if this is the first attempt, if the controller has changed or if an exception was thrown in a previous
                  // attempt, connect to the most recent controller
                  if (ioException || broker != prevController) {

                    ioException = false

                    if (prevController != null)
                      networkClient.close(prevController.idString)

                    prevController = broker
                    metadataUpdater.setNodes(Seq(prevController).asJava)
                  }
                case None =>
                  info(s"Broker registration for controller $controllerId is not available in the metadata cache")
              }
            case None =>
              info("No controller present in the metadata cache")
          }

          // 2. issue a controlled shutdown to the controller
          if (prevController != null) {
            try {

              if (!NetworkClientUtils.awaitReady(networkClient, prevController, time, socketTimeoutMs))
                throw new SocketTimeoutException(s"Failed to connect within $socketTimeoutMs ms")

              // send the controlled shutdown request
              val controlledShutdownApiVersion: Short =
                if (config.interBrokerProtocolVersion < KAFKA_0_9_0) 0
                else if (config.interBrokerProtocolVersion < KAFKA_2_2_IV0) 1
                else if (config.interBrokerProtocolVersion < KAFKA_2_4_IV1) 2
                else 3

              val controlledShutdownRequest = new ControlledShutdownRequest.Builder(
                new ControlledShutdownRequestData()
                  .setBrokerId(config.brokerId)
                  .setBrokerEpoch(kafkaController.brokerEpoch),
                controlledShutdownApiVersion)
              val request = networkClient.newClientRequest(prevController.idString, controlledShutdownRequest,
                time.milliseconds(), true)
              val clientResponse = NetworkClientUtils.sendAndReceive(networkClient, request, time)

              val shutdownResponse = clientResponse.responseBody.asInstanceOf[ControlledShutdownResponse]
              if (shutdownResponse.error == Errors.NONE && shutdownResponse.data.remainingPartitions.isEmpty) {
                shutdownSucceeded = true
                info("Controlled shutdown succeeded")
              }
              else {
                info(s"Remaining partitions to move: ${shutdownResponse.data.remainingPartitions}")
                info(s"Error from controller: ${shutdownResponse.error}")
              }
            }
            catch {
              case ioe: IOException =>
                ioException = true
                warn("Error during controlled shutdown, possibly because leader movement took longer than the " +
                  s"configured controller.socket.timeout.ms and/or request.timeout.ms: ${ioe.getMessage}")
              // ignore and try again
            }
          }
          if (!shutdownSucceeded) {
            Thread.sleep(config.controlledShutdownRetryBackoffMs)
            warn("Retrying controlled shutdown after the previous attempt failed...")
          }
        }
      }
      finally
        networkClient.close()

      shutdownSucceeded
    }

    if (startupComplete.get() && config.controlledShutdownEnable) {
      // We request the controller to do a controlled shutdown. On failure, we backoff for a configured period
      // of time and try again for a configured number of retries. If all the attempt fails, we simply force
      // the shutdown.
      info("Starting controlled shutdown")

      _brokerState = BrokerState.PENDING_CONTROLLED_SHUTDOWN

      val shutdownSucceeded = doControlledShutdown(config.controlledShutdownMaxRetries.intValue)

      if (!shutdownSucceeded)
        warn("Proceeding to do an unclean shutdown as all the controlled shutdown attempts failed")
    }
  }

  /**
   * Shutdown API for shutting down a single instance of the Kafka server.
   * Shuts down the LogManager, the SocketServer and the log cleaner scheduler thread
   */
  override def shutdown(): Unit = {
    try {
      info("shutting down")

      if (isStartingUp.get)
        throw new IllegalStateException("Kafka server is still starting up, cannot shut down!")

      // To ensure correct behavior under concurrent calls, we need to check `shutdownLatch` first since it gets updated
      // last in the `if` block. If the order is reversed, we could shutdown twice or leave `isShuttingDown` set to
      // `true` at the end of this method.
      if (shutdownLatch.getCount > 0 && isShuttingDown.compareAndSet(false, true)) {
        CoreUtils.swallow(controlledShutdown(), this)
        _brokerState = BrokerState.SHUTTING_DOWN

        if (dynamicConfigManager != null)
          CoreUtils.swallow(dynamicConfigManager.shutdown(), this)

        // Stop socket server to stop accepting any more connections and requests.
        // Socket server will be shutdown towards the end of the sequence.
        if (socketServer != null)
          CoreUtils.swallow(socketServer.stopProcessingRequests(), this)
        if (dataPlaneRequestHandlerPool != null)
          CoreUtils.swallow(dataPlaneRequestHandlerPool.shutdown(), this)
        if (controlPlaneRequestHandlerPool != null)
          CoreUtils.swallow(controlPlaneRequestHandlerPool.shutdown(), this)

        if (dataPlaneRequestProcessor != null)
          CoreUtils.swallow(dataPlaneRequestProcessor.close(), this)
        if (controlPlaneRequestProcessor != null)
          CoreUtils.swallow(controlPlaneRequestProcessor.close(), this)
        CoreUtils.swallow(authorizer.foreach(_.close()), this)
        if (adminManager != null)
          CoreUtils.swallow(adminManager.shutdown(), this)

        if (transactionCoordinator != null)
          CoreUtils.swallow(transactionCoordinator.shutdown(), this)
        if (groupCoordinator != null)
          CoreUtils.swallow(groupCoordinator.shutdown(), this)

        if (tokenManager != null)
          CoreUtils.swallow(tokenManager.shutdown(), this)

        if (replicaManager != null)
          CoreUtils.swallow(replicaManager.shutdown(), this)

        if (alterIsrManager != null)
          CoreUtils.swallow(alterIsrManager.shutdown(), this)

        if (clientToControllerChannelManager != null)
          CoreUtils.swallow(clientToControllerChannelManager.shutdown(), this)

        if (logManager != null)
          CoreUtils.swallow(logManager.shutdown(), this)
        // be sure to shutdown scheduler after log manager
        if (kafkaScheduler != null)
          CoreUtils.swallow(kafkaScheduler.shutdown(), this)

        if (kafkaController != null)
          CoreUtils.swallow(kafkaController.shutdown(), this)

        if (featureChangeListener != null)
          CoreUtils.swallow(featureChangeListener.close(), this)

        if (zkClient != null)
          CoreUtils.swallow(zkClient.close(), this)

        if (quotaManagers != null)
          CoreUtils.swallow(quotaManagers.shutdown(), this)

        // Even though socket server is stopped much earlier, controller can generate
        // response for controlled shutdown request. Shutdown server at the end to
        // avoid any failures (e.g. when metrics are recorded)
        if (socketServer != null)
          CoreUtils.swallow(socketServer.shutdown(), this)
        if (metrics != null)
          CoreUtils.swallow(metrics.close(), this)
        if (brokerTopicStats != null)
          CoreUtils.swallow(brokerTopicStats.close(), this)

        // Clear all reconfigurable instances stored in DynamicBrokerConfig
        config.dynamicConfig.clear()

        _brokerState = BrokerState.NOT_RUNNING

        startupComplete.set(false)
        isShuttingDown.set(false)
        CoreUtils.swallow(AppInfoParser.unregisterAppInfo(Server.MetricsPrefix, config.brokerId.toString, metrics), this)
        shutdownLatch.countDown()
        info("shut down completed")
      }
    }
    catch {
      case e: Throwable =>
        fatal("Fatal error during KafkaServer shutdown.", e)
        isShuttingDown.set(false)
        throw e
    }
  }

  /**
   * After calling shutdown(), use this API to wait until the shutdown is complete
   */
  override def awaitShutdown(): Unit = shutdownLatch.await()

  def getLogManager: LogManager = logManager

  def boundPort(listenerName: ListenerName): Int = socketServer.boundPort(listenerName)

  /** Return advertised listeners with the bound port (this may differ from the configured port if the latter is `0`). */
  def advertisedListeners: Seq[EndPoint] = {
    config.advertisedListeners.map { endPoint =>
      endPoint.copy(port = boundPort(endPoint.listenerName))
    }
  }

  /**
   * Checkpoint the BrokerMetadata to all the online log.dirs
   *
   * @param brokerMetadata
   */
  private def checkpointBrokerMetadata(brokerMetadata: ZkMetaProperties) = {
    for (logDir <- config.logDirs if logManager.isLogDirOnline(new File(logDir).getAbsolutePath)) {
      val checkpoint = brokerMetadataCheckpoints(logDir)
      checkpoint.write(brokerMetadata.toProperties)
    }
  }

  /**
   * Generates new brokerId if enabled or reads from meta.properties based on following conditions
   * <ol>
   * <li> config has no broker.id provided and broker id generation is enabled, generates a broker.id based on Zookeeper's sequence
   * <li> config has broker.id and meta.properties contains broker.id if they don't match throws InconsistentBrokerIdException
   * <li> config has broker.id and there is no meta.properties file, creates new meta.properties and stores broker.id
   * <ol>
   *
   * @return The brokerId.
   */
  private def getOrGenerateBrokerId(brokerMetadata: RawMetaProperties): Int = {
    val brokerId = config.brokerId

    if (brokerId >= 0 && brokerMetadata.brokerId.exists(_ != brokerId))
      throw new InconsistentBrokerIdException(
        s"Configured broker.id $brokerId doesn't match stored broker.id ${brokerMetadata.brokerId} in meta.properties. " +
          s"If you moved your data, make sure your configured broker.id matches. " +
          s"If you intend to create a new broker, you should remove all data in your data directories (log.dirs).")
    else if (brokerMetadata.brokerId.isDefined)
      brokerMetadata.brokerId.get
    else if (brokerId < 0 && config.brokerIdGenerationEnable) // generate a new brokerId from Zookeeper
      generateBrokerId()
    else
      brokerId
  }

  /**
   * Return a sequence id generated by updating the broker sequence id path in ZK.
   * Users can provide brokerId in the config. To avoid conflicts between ZK generated
   * sequence id and configured brokerId, we increment the generated sequence id by KafkaConfig.MaxReservedBrokerId.
   */
  private def generateBrokerId(): Int = {
    try {
      zkClient.generateBrokerSequenceId() + config.maxReservedBrokerId
    } catch {
      case e: Exception =>
        error("Failed to generate broker.id due to ", e)
        throw new GenerateBrokerIdException("Failed to generate broker.id", e)
    }
  }
}
