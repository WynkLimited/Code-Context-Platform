package com.wynk.codecontext.ops;

import org.slf4j.MDC;
import java.util.Map;
import java.util.UUID;

/** Durable job identity belongs to the worker thread, not to an earlier servlet request. */
public final class IndexingLogContext implements AutoCloseable {
    private final Map<String,String> previous;
    private IndexingLogContext(UUID job,UUID repository,int attempt) {
        previous=MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("job_id",job.toString());MDC.put("repository_id",repository.toString());
        MDC.put("job_attempt",Integer.toString(attempt));
    }
    public static IndexingLogContext open(UUID job,UUID repository,int attempt) {
        if(job==null||repository==null||attempt<1)throw new IllegalArgumentException("invalid indexing identity");
        return new IndexingLogContext(job,repository,attempt);
    }
    public static void commit(String sha) {
        if(sha==null||!sha.matches("[0-9a-fA-F]{40}"))throw new IllegalArgumentException("invalid indexing commit");
        MDC.put("commit_sha",sha);
    }
    public static void generation(UUID generation) {MDC.put("generation_id",generation.toString());}
    @Override public void close() {
        MDC.clear();if(previous!=null)MDC.setContextMap(previous);
    }
}
