package net.corda.bridge.services.supervisors

import net.corda.bridge.services.api.*
import net.corda.bridge.services.receiver.BridgeAMQPListenerServiceImpl
import net.corda.bridge.services.receiver.FloatControlListenerService
import net.corda.nodeapi.internal.lifecycle.ServiceStateCombiner
import net.corda.nodeapi.internal.lifecycle.ServiceStateHelper
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.lifecycle.ServiceStateSupport
import org.slf4j.LoggerFactory
import rx.Subscription

/**
 * @see FloatSupervisorService
 */
class FloatSupervisorServiceImpl(val conf: FirewallConfiguration,
                                 val auditService: FirewallAuditService,
                                 private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : FloatSupervisorService, ServiceStateSupport by stateHelper {
    companion object {
        private val log = contextLogger()
        private val consoleLogger = LoggerFactory.getLogger("BasicInfo")
    }

    override val amqpListenerService: BridgeAMQPListenerService
    private var floatControlService: FloatControlListenerService? = null
    private val statusFollower: ServiceStateCombiner
    private var statusSubscriber: Subscription? = null

    init {
        amqpListenerService = BridgeAMQPListenerServiceImpl(conf, auditService, extSourceSupplier = {
            floatControlService?.extCrlSource ?: throw IllegalStateException("floatControlService is null")
        })
        floatControlService = if (conf.firewallMode == FirewallMode.FloatOuter) {
            require(conf.haConfig == null) { "Float process should not have HA config, that is controlled via the bridge." }
            FloatControlListenerService(conf, auditService, amqpListenerService)
        } else {
            null
        }
        statusFollower = ServiceStateCombiner(listOfNotNull(amqpListenerService, floatControlService))
        activeChange.subscribe({
            consoleLogger.info("FloatSupervisorService: active = $it")
        }, { log.error("Error in state change", it) })
    }

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
        amqpListenerService.start()
        floatControlService?.start()
    }

    override fun stop() {
        stateHelper.active = false
        floatControlService?.stop()
        floatControlService = null
        amqpListenerService.stop()
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }
}