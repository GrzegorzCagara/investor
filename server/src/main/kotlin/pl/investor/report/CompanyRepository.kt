package pl.investor.report

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.source.avanza.AvanzaIdentity
import java.sql.ResultSet

@Repository
class CompanyRepository(private val jdbc: JdbcClient) {

    fun save(company: Company): Company {
        val id = jdbc.sql(
            """
            INSERT INTO company (name, isin, market, ticker, biznesradar_symbol, publishes_monthly_sales, short_name, avanza_id, listing, quote_currency)
            VALUES (:name, :isin, :market, :ticker, :biznesradarSymbol, :publishesMonthlySales, :shortName, :avanzaId, :listing, :quoteCurrency)
            RETURNING id
            """
        )
            .param("name", company.name)
            .param("isin", company.isin)
            .param("market", company.market.name)
            .param("ticker", company.ticker)
            .param("biznesradarSymbol", company.biznesradarSymbol)
            .param("publishesMonthlySales", company.publishesMonthlySales)
            .param("shortName", company.shortName)
            .param("avanzaId", company.avanzaId)
            .param("listing", company.listing)
            .param("quoteCurrency", company.quoteCurrency)
            .query(Long::class.java)
            .single()
        return company.copy(id = id)
    }

    fun markPublishesMonthlySales(id: Long) {
        jdbc.sql("UPDATE company SET publishes_monthly_sales = TRUE WHERE id = :id")
            .param("id", id)
            .update()
    }

    fun updateBiznesradarIdentity(id: Long, symbol: String, shortName: String?) {
        jdbc.sql("UPDATE company SET biznesradar_symbol = :symbol, short_name = COALESCE(short_name, :shortName) WHERE id = :id")
            .param("symbol", symbol)
            .param("shortName", shortName)
            .param("id", id)
            .update()
    }

    fun updateAvanzaIdentity(id: Long, identity: AvanzaIdentity) {
        jdbc.sql(
            """
            UPDATE company SET avanza_id = :avanzaId, ticker = :ticker, listing = :listing, quote_currency = :quoteCurrency,
                short_name = COALESCE(short_name, :shortName),
                isin = CASE
                    WHEN EXISTS (SELECT 1 FROM company other WHERE other.isin = :isin AND other.market = company.market AND other.id <> :id) THEN isin
                    ELSE :isin
                END
            WHERE id = :id
            """
        )
            .param("avanzaId", identity.avanzaId)
            .param("ticker", identity.ticker)
            .param("listing", identity.listing)
            .param("shortName", identity.shortName)
            .param("quoteCurrency", identity.quoteCurrency)
            .param("isin", identity.isin)
            .param("id", id)
            .update()
    }

    fun existsInMarkets(isin: String, markets: Collection<Market>): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM company WHERE isin = :isin AND market IN (:markets))")
            .param("isin", isin)
            .param("markets", markets.map { it.name })
            .query(Boolean::class.java)
            .single()

    fun namesInMarkets(markets: Collection<Market>): List<String> =
        jdbc.sql("SELECT name FROM company WHERE market IN (:markets)")
            .param("markets", markets.map { it.name })
            .query { rs, _ -> rs.getString("name") }
            .list()

    fun findInMarkets(markets: Collection<Market>): List<Company> =
        jdbc.sql("SELECT * FROM company WHERE market IN (:markets) ORDER BY id")
            .param("markets", markets.map { it.name })
            .query { rs, _ -> rs.toCompany() }
            .list()

    fun updateXtbAvailability(ids: Collection<Long>, available: Boolean) {
        if (ids.isEmpty()) return
        jdbc.sql("UPDATE company SET xtb_available = :available WHERE id IN (:ids)")
            .param("available", available)
            .param("ids", ids)
            .update()
    }

    fun findByIsin(isin: String, market: Market): Company? =
        jdbc.sql("SELECT * FROM company WHERE isin = :isin AND market = :market")
            .param("isin", isin)
            .param("market", market.name)
            .query { rs, _ -> rs.toCompany() }
            .optional()
            .orElse(null)

    fun findByName(name: String, market: Market? = null): Company? =
        jdbc.sql("SELECT * FROM company WHERE name = :name AND (CAST(:market AS TEXT) IS NULL OR market = :market) ORDER BY id LIMIT 1")
            .param("name", name)
            .param("market", market?.name)
            .query { rs, _ -> rs.toCompany() }
            .optional()
            .orElse(null)

    fun findById(id: Long): Company =
        jdbc.sql("SELECT * FROM company WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.toCompany() }
            .single()

    private fun ResultSet.toCompany() = Company(
        id = getLong("id"),
        name = getString("name"),
        isin = getString("isin"),
        market = Market.valueOf(getString("market")),
        ticker = getString("ticker"),
        biznesradarSymbol = getString("biznesradar_symbol"),
        publishesMonthlySales = getBoolean("publishes_monthly_sales"),
        shortName = getString("short_name"),
        avanzaId = getString("avanza_id"),
        listing = getString("listing"),
        quoteCurrency = getString("quote_currency"),
        availableOnXtb = getBoolean("xtb_available"),
    )
}
