package com.university.docassistant.config;

import com.university.docassistant.service.storage.LocalStorageService;
import com.university.docassistant.service.storage.LocalStorageService.MigrationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * US-03: encrypts the files the local storage still holds in plain form (stored before STORAGE_ENCRYPTION_KEY was
 * set), see {@link LocalStorageService#encryptLegacyFiles()}. Idempotent and safe if interrupted. Runs first among
 * the startup jobs, before AiProcessingRecovery queues OCR jobs that read stored files. Nothing to do with the S3
 * driver or while encryption is off.
 */
@Slf4j
@Component
@Order(-10)
public class StorageEncryptionMigration implements ApplicationRunner {

    private final ObjectProvider<LocalStorageService> localStorage;

    public StorageEncryptionMigration(ObjectProvider<LocalStorageService> localStorage) {
        this.localStorage = localStorage;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalStorageService storage = localStorage.getIfAvailable();
        if (storage == null || !storage.isEncrypting()) {
            return;
        }
        MigrationResult result = storage.encryptLegacyFiles();
        if (result.encrypted() > 0 || result.failed() > 0) {
            log.info("Storage encryption: {} old plain file(s) encrypted, {} failed (retried on the next start)",
                    result.encrypted(), result.failed());
        } else {
            log.info("Storage encryption: no plain files left to encrypt");
        }
    }
}
