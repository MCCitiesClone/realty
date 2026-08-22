package io.github.md5sha256.realty.database.maria;

import io.github.md5sha256.realty.DatabaseSettings;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaContractMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaLeaseholdContractMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaLeaseholdModificationMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaRealtyRegionMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaLeaseholdHistoryMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractAuctionMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdHistoryMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractBidMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractOfferMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractBidPaymentMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractOfferPaymentMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaAgentHistoryMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractAgentInviteMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaFreeholdContractSanctionedAuctioneerMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaRealtySignMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaRegionTagMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaGovernmentPartyMapper;
import io.github.md5sha256.realty.database.maria.mapper.MariaSearchMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

public class MariaDatabase implements Database {

    private final DatabaseSettings settings;
    private final PooledDataSource dataSource;
    private final SqlSessionFactory sessionFactory;
    private final Logger logger;
    /** Resolved once so migration and the pool agree on which time-zone pinning the server accepts. */
    private final String jdbcUrl;

    public MariaDatabase(@NotNull DatabaseSettings settings, @NotNull Logger logger) {
        this.settings = settings;
        this.jdbcUrl = resolveJdbcUrl(settings, logger);
        this.dataSource = new PooledDataSource("org.mariadb.jdbc.Driver", this.jdbcUrl, settings.username(), settings.password());
        this.dataSource.setPoolPingEnabled(true);
        this.dataSource.setPoolPingQuery("SELECT 1");
        this.dataSource.setPoolPingConnectionsNotUsedFor(600000);
        this.sessionFactory = buildSessionFactory(this.dataSource);
        this.logger = logger;
    }

    /**
     * Pins the connection's time zone to the JVM's default, degrading to a form the server accepts.
     *
     * <p>The codebase writes {@link java.time.LocalDateTime} values produced by the JVM into
     * {@code DATETIME} columns and compares them in SQL against {@code NOW()}. If the database
     * server's time zone differs from the JVM's, those two clocks disagree and every deadline
     * sweep (terminations, expiring bid/offer payments, lease expiry) fires early or never.
     * {@code connectionTimeZone=LOCAL} plus {@code forceConnectionTimeZoneToSession=true} makes
     * the driver set the session {@code time_zone} to the JVM default, so {@code NOW()} agrees
     * with {@code LocalDateTime.now()}.
     *
     * <p>That form names the zone ({@code SET time_zone = 'America/New_York'}), which a server only
     * understands once its {@code mysql.time_zone*} tables have been populated by
     * {@code mysql_tzinfo_to_sql}. On a server without them the connection is refused outright with
     * "Unknown or incorrect time zone", which used to take the whole plugin down at enable. So the
     * pinning degrades rather than fails: first the zone name, then the JVM's current UTC offset
     * (which needs no lookup tables), then no pinning at all. A correct clock is worth one extra
     * connection at startup; being unable to start is not.
     *
     * <p>An explicit {@code connectionTimeZone} in the configured URL is the operator's decision and
     * is left untouched, unprobed.
     *
     * @param settings the database settings holding the configured URL
     * @param logger   logger for reporting a degraded pinning
     * @return the JDBC URL to connect with
     */
    @NotNull
    private static String resolveJdbcUrl(@NotNull DatabaseSettings settings, @NotNull Logger logger) {
        return resolveJdbcUrl(settings, logger, MariaDatabase::canConnect);
    }

    /**
     * @param connectivity tests whether a candidate URL can open a connection; injectable so the
     *                     fallback ladder can be tested without a server
     */
    @NotNull
    static String resolveJdbcUrl(@NotNull DatabaseSettings settings, @NotNull Logger logger,
                                 @NotNull BiPredicate<String, DatabaseSettings> connectivity) {
        String base = "jdbc:" + settings.url();
        if (base.contains("connectionTimeZone=")) {
            return base;
        }
        String named = withTimeZone(base, "LOCAL");
        if (connectivity.test(named, settings)) {
            return named;
        }
        // ZoneOffset renders as "-04:00", or "Z" for UTC, which no server accepts as a time zone.
        String offset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getId();
        String offsetUrl = withTimeZone(base, offset.equals("Z") ? "+00:00" : offset);
        if (connectivity.test(offsetUrl, settings)) {
            logger.warning("The database does not recognise the time zone name '"
                    + ZoneId.systemDefault() + "'; pinning connections to the fixed offset " + offset
                    + " instead. Deadlines stay correct until this offset changes — restart after a"
                    + " daylight-saving transition, or load the server's time zone tables with"
                    + " mysql_tzinfo_to_sql to have the zone tracked properly.");
            return offsetUrl;
        }
        logger.warning("Could not pin the database connection's time zone; falling back to the URL"
                + " as configured. If the database server's time zone differs from this server's ("
                + ZoneId.systemDefault() + "), lease expiry, terminations and payment deadlines will"
                + " fire at the wrong time. Set the two to the same zone, or add connectionTimeZone"
                + " to the configured URL.");
        return base;
    }

    @NotNull
    private static String withTimeZone(@NotNull String url, @NotNull String zone) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectionTimeZone=" + zone + "&forceConnectionTimeZoneToSession=true";
    }

    /**
     * Opens and immediately closes a connection, reporting only whether it succeeded. A failure for
     * any other reason (host down, bad credentials) also returns false, which just means the ladder
     * runs to the end and the real error surfaces from the migration that follows.
     */
    private static boolean canConnect(@NotNull String url, @NotNull DatabaseSettings settings) {
        try (Connection ignored = DriverManager.getConnection(url, settings.username(), settings.password())) {
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    @Override
    public void close() {
        this.dataSource.forceCloseAll();
    }

    @NotNull
    private static SqlSessionFactory buildSessionFactory(@NotNull DataSource dataSource) {
        Environment environment = new Environment("production", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, UUIDAsBin16Handler.class);
        configuration.addMapper(MariaContractMapper.class);
        configuration.addMapper(MariaLeaseholdContractMapper.class);
        configuration.addMapper(MariaLeaseholdModificationMapper.class);
        configuration.addMapper(MariaRealtyRegionMapper.class);
        configuration.addMapper(MariaFreeholdHistoryMapper.class);
        configuration.addMapper(MariaLeaseholdHistoryMapper.class);
        configuration.addMapper(MariaFreeholdContractAuctionMapper.class);
        configuration.addMapper(MariaFreeholdContractBidMapper.class);
        configuration.addMapper(MariaFreeholdContractMapper.class);
        configuration.addMapper(MariaFreeholdContractOfferMapper.class);
        configuration.addMapper(MariaFreeholdContractBidPaymentMapper.class);
        configuration.addMapper(MariaFreeholdContractOfferPaymentMapper.class);
        configuration.addMapper(MariaFreeholdContractSanctionedAuctioneerMapper.class);
        configuration.addMapper(MariaFreeholdContractAgentInviteMapper.class);
        configuration.addMapper(MariaAgentHistoryMapper.class);
        configuration.addMapper(MariaRealtySignMapper.class);
        configuration.addMapper(MariaRegionTagMapper.class);
        configuration.addMapper(MariaGovernmentPartyMapper.class);
        configuration.addMapper(MariaSearchMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override
    public void initializeSchema(@NotNull Path schemaFilesDirectory) throws IOException, SQLException {
        MariaSchemaMigrator.migrate(this.jdbcUrl, this.settings.username(), this.settings.password(),
                schemaFilesDirectory, MariaSchemaMigrator.defaultMigrations(), this.logger);
    }

    @Override
    public @NotNull SqlSessionWrapper openSession() {
        return new MariaSqlSession(this.sessionFactory.openSession());
    }

    @Override
    public @NotNull SqlSessionWrapper openSession(boolean autoCommit) {
        return new MariaSqlSession(this.sessionFactory.openSession(autoCommit));
    }

    @Override
    public @NotNull SqlSessionWrapper openSession(@NotNull ExecutorType executorType, boolean autoCommit) {
        return new MariaSqlSession(this.sessionFactory.openSession(executorType, autoCommit));
    }
}
