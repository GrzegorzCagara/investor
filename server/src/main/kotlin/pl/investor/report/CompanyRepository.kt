package pl.investor.report

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class CompanyRepository(private val jdbc: JdbcClient) {

    fun save(company: Company): Company {
        val id = jdbc.sql(
            """
            INSERT INTO company (name, isin, market, ticker, biznesradar_symbol, publishes_monthly_sales, short_name)
            VALUES (:name, :isin, :market, :ticker, :biznesradarSymbol, :publishesMonthlySales, :shortName)
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

    fun findByIsin(isin: String): Company? =
        jdbc.sql("SELECT * FROM company WHERE isin = :isin")
            .param("isin", isin)
            .query { rs, _ -> rs.toCompany() }
            .optional()
            .orElse(null)

    fun findByName(name: String): Company? =
        jdbc.sql("SELECT * FROM company WHERE name = :name ORDER BY id LIMIT 1")
            .param("name", name)
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
    )
}
