package net.corda.node.utilities

import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.notary.NotaryService
import net.corda.core.utilities.contextLogger
import net.corda.node.SerialFilter
import net.corda.node.VersionInfo
import net.corda.node.internal.cordapp.VirtualCordapp
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.config.NotaryConfig
import net.corda.nodeapi.internal.cordapp.CordappLoader
import net.corda.notary.experimental.bftsmart.BFTSmartNotaryService
import net.corda.notary.experimental.raft.RaftNotaryService
import net.corda.notary.jpa.JPANotaryService
import java.lang.reflect.InvocationTargetException

class NotaryLoader(
        private val config: NotaryConfig,
        versionInfo: VersionInfo
) {
    companion object {
        private val log = contextLogger()
    }

    /**
     * A virtual CorDapp containing the notary implementation if one of the built-in notaries is used.
     * [Null] if a notary implementation is expected to be loaded from an external CorDapp.
     */
    val builtInNotary: CordappImpl?
    private val builtInServiceClass: Class<out NotaryService>?

    init {
        builtInServiceClass = if (config.className.isNullOrBlank()) {
            // Using a built-in notary
            when {
                config.bftSMaRt != null -> {
                    builtInNotary = VirtualCordapp.generateBFTSmartNotary(versionInfo)
                    BFTSmartNotaryService::class.java
                }
                config.raft != null -> {
                    builtInNotary = VirtualCordapp.generateRaftNotary(versionInfo)
                    RaftNotaryService::class.java
                }
                else -> {
                    builtInNotary = VirtualCordapp.generateJPANotary(versionInfo)
                    JPANotaryService::class.java
                }
            }
        } else {
            // Using a notary from an external CorDapp
            builtInNotary = null
            null
        }
    }

    fun loadService(myNotaryIdentity: PartyAndCertificate?, services: ServiceHubInternal, cordappLoader: CordappLoader): NotaryService {

        val notaryParty = myNotaryIdentity?.party
                ?: throw IllegalArgumentException("Unable to start notary service: notary identity not found")

        warnIfNotaryNotWhitelisted(myNotaryIdentity, services)
        validateNotaryType(myNotaryIdentity, services)

        val serviceClass = builtInServiceClass ?: scanCorDapps(cordappLoader)
        log.info("Starting notary service: $serviceClass")

        /** Some notary implementations only work with Java serialization. */
        maybeInstallSerializationFilter(serviceClass)

        val constructor = serviceClass
                .getDeclaredConstructor(ServiceHubInternal::class.java, Party::class.java)
                .apply { isAccessible = true }
        try {
            return constructor.newInstance(services, notaryParty)
        } catch (e: InvocationTargetException) {
            log.error("Exception occurred when starting notary service")
            throw e.cause ?: e
        }
    }

    /** Log a warning if the notary is not whitelisted in the network parameters. */
    private fun warnIfNotaryNotWhitelisted(myNotaryIdentity: PartyAndCertificate, services: ServiceHubInternal) {
        val ourParty = myNotaryIdentity.party
        if (!services.networkMapCache.isNotary(ourParty)) {
            log.warn("Provided notary identity is not in whitelist")
        }
    }

    /**
     * Validates that the notary is correctly configured by comparing the configured type against the
     * type advertised in the network map cache.
     */
    private fun validateNotaryType(myNotaryIdentity: PartyAndCertificate, services: ServiceHubInternal) {
        val configuredAsValidatingNotary = services.configuration.notary?.validating
        val notaryParty = myNotaryIdentity.party
        val validatingNotaryInNetworkMapCache = services.networkMapCache.isValidatingNotary(notaryParty)
        
        if(configuredAsValidatingNotary != validatingNotaryInNetworkMapCache) {
            log.warn("There is a discrepancy in the configured notary type and the one advertised in the network parameters. " +
            "Configured as validating: $configuredAsValidatingNotary. Advertised as validating: $validatingNotaryInNetworkMapCache")
        }
    }
    
    /** Looks for the config specified notary service implementation in loaded CorDapps. This mechanism is for internal use only. */
    private fun scanCorDapps(cordappLoader: CordappLoader): Class<out NotaryService> {
        val loadedImplementations = cordappLoader.cordapps.mapNotNull { it.notaryService }
        log.debug("Notary service implementations found: ${loadedImplementations.joinToString(", ")}")
        return loadedImplementations.firstOrNull { it.name == config.className }
                ?: throw IllegalArgumentException("The notary service implementation specified in the configuration: ${config.className} is not found. Available implementations: ${loadedImplementations.joinToString(", ")}}")
    }

    /** Installs a custom serialization filter defined by a notary service implementation. Only supported in dev mode. */
    private fun maybeInstallSerializationFilter(serviceClass: Class<out NotaryService>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val filter = serviceClass
                    .getDeclaredMethod("getSerializationFilter")
                    .invoke(null) as ((Class<*>) -> Boolean)
            SerialFilter.install(filter)
        } catch (e: NoSuchMethodException) {
            // No custom serialization filter declared
        }
    }
}