package com.wynk.codecontext.ops;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class IndexingLogContextTest {
    @Test void isolatesWorkerIdentityAndRestoresThePreviousContextAfterFailure() {
        var previous=MDC.getCopyOfContextMap();
        try {
            MDC.setContextMap(Map.of("request_id","previous-servlet","unrelated","preserved"));
            UUID job=UUID.randomUUID(),repository=UUID.randomUUID(),generation=UUID.randomUUID();
            assertThrows(IllegalStateException.class,()->{
                try(var ignored=IndexingLogContext.open(job,repository,2)) {
                    assertEquals(Map.of("job_id",job.toString(),"repository_id",repository.toString(),"job_attempt","2"),MDC.getCopyOfContextMap());
                    IndexingLogContext.commit("a".repeat(40));IndexingLogContext.generation(generation);
                    assertEquals("a".repeat(40),MDC.get("commit_sha"));assertEquals(generation.toString(),MDC.get("generation_id"));
                    throw new IllegalStateException("fixture failure");
                }
            });
            assertEquals(Map.of("request_id","previous-servlet","unrelated","preserved"),MDC.getCopyOfContextMap());
        } finally {MDC.clear();if(previous!=null)MDC.setContextMap(previous);}
    }
    @Test void reusedWorkerThreadDoesNotRetainThePreviousJobOrCommit() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(int i=0;i<2;i++) {
                UUID job=UUID.randomUUID();
                executor.submit(()->{
                    assertTrue(MDC.getCopyOfContextMap()==null||MDC.getCopyOfContextMap().isEmpty());
                    try(var ignored=IndexingLogContext.open(job,UUID.randomUUID(),1)) {
                        assertEquals(job.toString(),MDC.get("job_id"));assertNull(MDC.get("commit_sha"));
                        IndexingLogContext.commit("b".repeat(40));
                    }
                    assertTrue(MDC.getCopyOfContextMap()==null||MDC.getCopyOfContextMap().isEmpty());
                }).get(5,TimeUnit.SECONDS);
            }
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void rejectsUntrustedIdentityBeforeModifyingContext() {
        var before=MDC.getCopyOfContextMap();
        assertThrows(IllegalArgumentException.class,()->IndexingLogContext.open(null,UUID.randomUUID(),1));
        assertThrows(IllegalArgumentException.class,()->IndexingLogContext.commit("unsafe\nvalue"));
        assertEquals(before,MDC.getCopyOfContextMap());
    }
}
