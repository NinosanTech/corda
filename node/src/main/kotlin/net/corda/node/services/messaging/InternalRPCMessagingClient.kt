package net.corda.node.services.messaging

import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.RPCOps
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.security.RPCSecurityManager
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.NODE_RPC_USER
import net.corda.nodeapi.internal.ArtemisTcpTransport
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.ServerLocator
import org.apache.activemq.artemis.api.core.management.ActiveMQServerControl

/**
 * Used by the Node to communicate with the RPC broker.
 */
class InternalRPCMessagingClient<OPS : RPCOps>(val sslConfig: MutualSslConfiguration, val serverAddress: NetworkHostAndPort, val maxMessageSize: Int, val nodeName: CordaX500Name, val rpcServerConfiguration: RPCServerConfiguration) : SingletonSerializeAsToken(), AutoCloseable {
    private var locator: ServerLocator? = null
    private var rpcServer: RPCServer<OPS>? = null

    fun init(rpcOps: OPS, securityManager: RPCSecurityManager) {
        init(RPCOpsRouting.singleton(nodeName, rpcOps), securityManager)
    }

    fun init(rpcOpsRouting: RPCOpsRouting<OPS>, securityManager: RPCSecurityManager) = synchronized(this) {

        val tcpTransport = ArtemisTcpTransport.rpcInternalClientTcpTransport(serverAddress, sslConfig)
        locator = ActiveMQClient.createServerLocatorWithoutHA(tcpTransport).apply {
            // Never time out on our loopback Artemis connections. If we switch back to using the InVM transport this
            // would be the default and the two lines below can be deleted.
            connectionTTL = 60000
            clientFailureCheckPeriod = 30000
            minLargeMessageSize = maxMessageSize
            isUseGlobalPools = nodeSerializationEnv != null
        }

        rpcServer = RPCServer(rpcOpsRouting, NODE_RPC_USER, NODE_RPC_USER, locator!!, securityManager, nodeName, rpcServerConfiguration)
    }

    fun start(serverControl: ActiveMQServerControl) = synchronized(this) {
        rpcServer!!.start(serverControl)
    }

    fun stop(): Unit = synchronized(this) {
        rpcServer?.close()
        locator?.close()
    }

    override fun close() = stop()
}
