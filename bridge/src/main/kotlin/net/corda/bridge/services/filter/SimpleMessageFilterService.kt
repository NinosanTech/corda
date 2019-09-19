package net.corda.bridge.services.filter

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.bridge.services.api.*
import net.corda.bridge.services.config.BridgeConfigHelper.FILTER_SERVICE_CACHE_EXPIRY_SECONDS
import net.corda.bridge.services.config.BridgeConfigHelper.FILTER_SERVICE_MAX_CACHE_SIZE
import net.corda.nodeapi.internal.lifecycle.ServiceStateCombiner
import net.corda.nodeapi.internal.lifecycle.ServiceStateHelper
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.utilities.measureMilliAndNanoTime
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2PMessagingHeaders
import net.corda.nodeapi.internal.lifecycle.ServiceStateSupport
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.ClientProducer
import org.apache.activemq.artemis.api.core.client.ClientSession
import rx.Subscription
import java.util.concurrent.TimeUnit

class SimpleMessageFilterService(val conf: FirewallConfiguration,
                                 val auditService: FirewallAuditService,
                                 private val artemisConnectionService: BridgeArtemisConnectionService,
                                 private val bridgeSenderService: BridgeSenderService,
                                 private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : IncomingMessageFilterService, ServiceStateSupport by stateHelper {
    companion object {
        val log = contextLogger()
    }

    private val statusFollower = ServiceStateCombiner(listOf(auditService, artemisConnectionService, bridgeSenderService))
    private var statusSubscriber: Subscription? = null
    private val whiteListedAMQPHeaders: Set<String> = conf.whitelistedHeaders.toSet()
    private var inboundSession: ClientSession? = null
    private var inboundProducer: ClientProducer? = null

    private val cache: Cache<String, String> = Caffeine.newBuilder()
            .maximumSize(java.lang.Long.getLong(FILTER_SERVICE_MAX_CACHE_SIZE, 100))
            .expireAfterWrite(java.lang.Long.getLong(FILTER_SERVICE_CACHE_EXPIRY_SECONDS, 10), TimeUnit.SECONDS)
            .build()

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            if (it) {
                try {
                    inboundSession = artemisConnectionService.started!!.sessionFactory.createSession(ArtemisMessagingComponent.NODE_P2P_USER, ArtemisMessagingComponent.NODE_P2P_USER, false, true, true, false, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE)
                    inboundProducer = inboundSession!!.createProducer()
                } catch (e: Exception) {
                    log.warn("Problems creating producer. Will bounce Artemis connection.", e)
                    artemisConnectionService.bounce()
                }
            } else {
                inboundProducer?.close()
                inboundProducer = null
                inboundSession?.close()
                inboundSession = null
            }
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
    }

    override fun stop() {
        inboundProducer?.close()
        inboundProducer = null
        inboundSession?.close()
        inboundSession = null
        stateHelper.active = false
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }

    private fun validateMessage(inboundMessage: ReceivedMessage) {
        val sourceLegalName = try {
            CordaX500Name.parse(inboundMessage.sourceLegalName)
        } catch (ex: IllegalArgumentException) {
            throw SecurityException("Invalid Legal Name ${inboundMessage.sourceLegalName}")
        }
        require(inboundMessage.payload.isNotEmpty()) { "No valid payload" }
        val validInboxTopic = bridgeSenderService.validateReceiveTopic(inboundMessage.topic, sourceLegalName)
        require(validInboxTopic) { "Topic not a legitimate Inbox for a node on this Artemis Broker ${inboundMessage.topic}" }
        require(inboundMessage.applicationProperties.keys.all { it in whiteListedAMQPHeaders }) { "Disallowed header present in ${inboundMessage.applicationProperties.keys}" }
    }

    override fun sendMessageToLocalBroker(inboundMessage: ReceivedMessage) {
        if (!active) {
            auditService.packetDropEvent(inboundMessage, "Packet arrived while dependencies down.", RoutingDirection.INBOUND)
            inboundMessage.complete(false) // redeliver.
            return
        }
        try {
            validateMessage(inboundMessage)
        } catch (ex: Exception) {
            auditService.packetDropEvent(inboundMessage, "Packet Failed validation checks: " + ex.message, RoutingDirection.INBOUND)
            inboundMessage.complete(true) // consume the bad message, so that it isn't redelivered forever.
            return
        }
        val msgId = inboundMessage.applicationProperties["_AMQ_DUPL_ID"]?.toString()
        try {
            val session = inboundSession
            val producer = inboundProducer
            if (session == null || producer == null) {
                throw IllegalStateException("No artemis connection to forward message over")
            }
            val artemisMessage = session.createMessage(true)
            for (key in whiteListedAMQPHeaders) {
                if (inboundMessage.applicationProperties.containsKey(key)) {
                    artemisMessage.putObjectProperty(key, inboundMessage.applicationProperties[key])
                }
            }
            artemisMessage.putStringProperty(P2PMessagingHeaders.bridgedCertificateSubject, SimpleString(inboundMessage.sourceLegalName))
            artemisMessage.writeBodyBufferBytes(inboundMessage.payload)
            log.debug { "Sending message [$msgId]" }
            val timeToSendMillis: Double = measureMilliAndNanoTime {
                producer.send(SimpleString(inboundMessage.topic), artemisMessage) { _ -> inboundMessage.complete(true) }
            }
            log.debug { "Sent message [$msgId] in ${timeToSendMillis}ms." }
            auditService.packetAcceptedEvent(inboundMessage, RoutingDirection.INBOUND)
            inboundMessage.release()
        } catch (ex: Exception) {
            val previousRecord = if (msgId != null) {
                cache.asMap().putIfAbsent(msgId, msgId)
            } else null

            fun getErrorMessage() = "Error trying to forward message with id: [$msgId]"

            if (previousRecord == null) {
                // Logging for the first time or if `msgId` is null which is also very suspicious.
                log.error(getErrorMessage(), ex)
            } else {
                log.trace(::getErrorMessage)
            }
            inboundMessage.complete(false) // delivery failure. NAK back to source and await re-delivery attempts
        }
    }
}