-- Lets a Treasury GOVERNMENT account stand in for a player as a region's authority, landlord,
-- title holder or tenant.
--
-- The four party columns (FreeholdContract.authorityId/titleHolderId, LeaseholdContract.
-- landlordId/tenantId) stay UUID: a government is addressed by a synthetic party UUID derived
-- deterministically from its Treasury accountId, so every existing query, index, uniqueness
-- constraint and equality check keeps working unchanged. This table is the only place the
-- synthetic UUID is tied back to the account it stands for.
--
-- accountId is UNIQUE so an account can never be reachable through two different party UUIDs;
-- partyUuid is the primary key because that is what the contract rows actually store.
-- displayName is a cache of the Treasury account's name for rendering parties whose account
-- has since been archived or renamed — Treasury remains the source of truth when reachable.
CREATE TABLE IF NOT EXISTS RealtyGovernmentParty
(
    partyUuid   UUID         NOT NULL PRIMARY KEY,
    accountId   INT          NOT NULL,
    displayName VARCHAR(255) NOT NULL,
    CONSTRAINT unique_government_accountId UNIQUE (accountId)
);
