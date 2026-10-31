package com.university.docassistant.service.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * No live S3 here: these only build the real client, which catches classpath problems (a missing or mismatched
 * HTTP implementation fails at construction), and check what is refused before any request is sent.
 */
class S3StorageServiceTest {

    @Test
    void buildsAClientForMinioWithoutContactingIt() {
        assertThatNoException().isThrownBy(() -> {
            S3StorageService storage = new S3StorageService("unidocs", "eu-central-1", "http://localhost:9000", true, "AES256");
            storage.close();
        });
    }

    @Test
    void bucketAndRegionAreRequired() {
        assertThatThrownBy(() -> new S3StorageService("", "eu-central-1", "", false, "AES256"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.storage.s3.bucket");
        assertThatThrownBy(() -> new S3StorageService("unidocs", " ", "", false, "AES256"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storedNamesThatAreNotGeneratedKeysNeverReachS3() {
        S3StorageService storage = new S3StorageService("unidocs", "eu-central-1", "http://localhost:9", true, "AES256");
        try {
            assertThatThrownBy(() -> storage.load("../other-bucket/x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.load("a/b")).isInstanceOf(IllegalArgumentException.class);
            // delete() never throws, also for a bad name.
            assertThatNoException().isThrownBy(() -> storage.delete("../x"));
            assertThat(storage).isNotNull();
        } finally {
            storage.close();
        }
    }

    @Test
    void everyPutAsksForServerSideEncryptionByDefault() {
        S3Client client = mock(S3Client.class);
        S3StorageService storage = new S3StorageService(client, "unidocs", "");

        storage.store(new byte[]{1, 2, 3});
        storage.store(new MockMultipartFile("file", "lab.pdf", "application/pdf", new byte[]{4, 5}));

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, times(2)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).allSatisfy(put -> {
            assertThat(put.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
            assertThat(put.bucket()).isEqualTo("unidocs");
        });
    }

    @Test
    void sseCanBeSwitchedOffForServersWithoutItAndOtherValuesFailFast() {
        S3Client client = mock(S3Client.class);
        new S3StorageService(client, "unidocs", "none").store(new byte[]{1});

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().serverSideEncryption()).isNull();
        assertThat(S3StorageService.parseSse("aes256")).isEqualTo(ServerSideEncryption.AES256);
        assertThatThrownBy(() -> S3StorageService.parseSse("aws:kms")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.storage.s3.sse");
    }
}
