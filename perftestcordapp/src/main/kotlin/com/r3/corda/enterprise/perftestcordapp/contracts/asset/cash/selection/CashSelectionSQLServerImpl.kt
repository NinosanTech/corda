package com.r3.corda.enterprise.perftestcordapp.contracts.asset.cash.selection

import net.corda.core.contracts.Amount
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.toBase58String
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.util.*

/**
 * SQL Server / SQL Azure
 */
class CashSelectionSQLServerImpl : AbstractCashSelection(maxRetries = 16, retrySleep = 1000, retryCap = 5000) {

    companion object {
        const val JDBC_DRIVER_NAME = "Microsoft JDBC Driver"
        private val log = contextLogger()
    }

    override fun isCompatible(metadata: DatabaseMetaData): Boolean {
        return metadata.driverName.startsWith(JDBC_DRIVER_NAME, ignoreCase = true)
    }

    override fun toString() = "${this::class.java} for $JDBC_DRIVER_NAME"

    override fun executeQuery(connection: Connection, amount: Amount<Currency>, lockId: UUID, notary: Party?,
                              onlyFromIssuerParties: Set<AbstractParty>, withIssuerRefs: Set<OpaqueBytes>, withResultSet: (ResultSet) -> Boolean): Boolean {

        val selectJoin = """
            WITH row(transaction_id, output_index, pennies, total, lock_id) AS
            (
            SELECT vs.transaction_id, vs.output_index, ccs.pennies,
            SUM(ccs.pennies) OVER (ORDER BY ccs.transaction_id RANGE UNBOUNDED PRECEDING), vs.lock_id
            FROM contract_pt_cash_states AS ccs, vault_states AS vs
            WHERE vs.transaction_id = ccs.transaction_id AND vs.output_index = ccs.output_index
                AND vs.state_status = 0
                AND ccs.ccy_code = ?
                AND (vs.lock_id = ? OR vs.lock_id is null)""" +
                (if (notary != null)
                    " AND vs.notary_name = ?" else "") +
                (if (onlyFromIssuerParties.isNotEmpty())
                    " AND ccs.issuer_key IN (?)" else "") +
                (if (withIssuerRefs.isNotEmpty())
                    " AND ccs.issuer_ref IN (?)" else "") +
                """)
            SELECT row.transaction_id, row.output_index, row.pennies, row.total, row.lock_id
            FROM row where row.total < ? + row.pennies"""

        // Use prepared statement for protection against SQL Injection
        connection.prepareStatement(selectJoin).use { statement ->
            var pIndex = 0
            statement.setString(++pIndex, amount.token.currencyCode)
            statement.setString(++pIndex, lockId.toString())
            if (notary != null)
                statement.setString(++pIndex, notary.name.toString())
            if (onlyFromIssuerParties.isNotEmpty())
                statement.setObject(++pIndex, onlyFromIssuerParties.map { it.owningKey.toBase58String() as Any }.toTypedArray())
            if (withIssuerRefs.isNotEmpty())
                statement.setObject(++pIndex, withIssuerRefs.map { it.bytes as Any }.toTypedArray())
            statement.setLong(++pIndex, amount.quantity)
            log.debug(selectJoin)

            statement.executeQuery().use { rs ->
                return withResultSet(rs)
            }
        }
    }
}