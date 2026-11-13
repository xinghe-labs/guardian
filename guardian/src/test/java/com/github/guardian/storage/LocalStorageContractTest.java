package com.github.guardian.storage;

import java.util.UUID;

/** 把 {@link GuardianStorageContractTest} 的契约套到本地实现上。 */
class LocalStorageContractTest extends GuardianStorageContractTest {

    private final LocalGuardianStorage storage = new LocalGuardianStorage();

    @Override
    protected GuardianStorage storage() {
        return storage;
    }

    @Override
    protected String uniqueKey(String suffix) {
        return "local:" + suffix + ":" + UUID.randomUUID();
    }
}
