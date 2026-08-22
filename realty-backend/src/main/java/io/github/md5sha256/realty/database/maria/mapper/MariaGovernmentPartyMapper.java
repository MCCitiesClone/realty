package io.github.md5sha256.realty.database.maria.mapper;

import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.database.mapper.GovernmentPartyMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for CRUD operations on the {@code RealtyGovernmentParty} table.
 *
 * <p>The table maps the synthetic UUID a contract row stores for a government back to the Treasury
 * account it stands for. Rows are written by registration, never by contract mutations: the four
 * party columns hold the UUID and know nothing about this table.
 *
 * @see GovernmentPartyEntity
 */
public interface MariaGovernmentPartyMapper extends GovernmentPartyMapper {

    /**
     * {@inheritDoc}
     *
     * <p>Keyed on the {@code accountId} unique constraint rather than the primary key: both
     * identify the same row (the party UUID is derived from the account id), but colliding on
     * {@code accountId} also repairs a row whose {@code partyUuid} predates a change in the
     * derivation.
     */
    @Override
    @Insert("""
            INSERT INTO RealtyGovernmentParty (partyUuid, accountId, displayName)
            VALUES (#{partyUuid}, #{accountId}, #{displayName})
            ON DUPLICATE KEY UPDATE partyUuid = #{partyUuid}, displayName = #{displayName}
            """)
    int upsert(@Param("partyUuid") @NotNull UUID partyUuid,
               @Param("accountId") int accountId,
               @Param("displayName") @NotNull String displayName);

    @Override
    @Select("""
            SELECT partyUuid, accountId, displayName
            FROM RealtyGovernmentParty
            WHERE partyUuid = #{partyUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "partyUuid", javaType = UUID.class),
            @Arg(column = "accountId", javaType = int.class),
            @Arg(column = "displayName", javaType = String.class)
    })
    @Nullable GovernmentPartyEntity selectByPartyUuid(@Param("partyUuid") @NotNull UUID partyUuid);

    @Override
    @Select("""
            SELECT partyUuid, accountId, displayName
            FROM RealtyGovernmentParty
            WHERE accountId = #{accountId}
            """)
    @ConstructorArgs({
            @Arg(column = "partyUuid", javaType = UUID.class),
            @Arg(column = "accountId", javaType = int.class),
            @Arg(column = "displayName", javaType = String.class)
    })
    @Nullable GovernmentPartyEntity selectByAccountId(@Param("accountId") int accountId);

    @Override
    @Select("""
            SELECT partyUuid, accountId, displayName
            FROM RealtyGovernmentParty
            ORDER BY displayName
            """)
    @ConstructorArgs({
            @Arg(column = "partyUuid", javaType = UUID.class),
            @Arg(column = "accountId", javaType = int.class),
            @Arg(column = "displayName", javaType = String.class)
    })
    @NotNull List<GovernmentPartyEntity> selectAll();

    @Override
    @Delete("""
            DELETE FROM RealtyGovernmentParty
            WHERE partyUuid = #{partyUuid}
            """)
    int deleteByPartyUuid(@Param("partyUuid") @NotNull UUID partyUuid);
}
