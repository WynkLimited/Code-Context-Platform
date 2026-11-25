package com.wynk.codecontext.index;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.search.ZoektIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationRetentionReconcileTest {
    @Test void reconcileEnqueuesOnlyAbsentUntrackedShards() throws Exception {
        UUID orphan=UUID.randomUUID(),live=UUID.randomUUID(),tracked=UUID.randomUUID();
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM index_generations WHERE id=?)"),eq(Boolean.class),eq(orphan))).thenReturn(false);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM index_generations WHERE id=?)"),eq(Boolean.class),eq(live))).thenReturn(true);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM index_generations WHERE id=?)"),eq(Boolean.class),eq(tracked))).thenReturn(false);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM generation_cleanup_outbox WHERE generation_id=?)"),eq(Boolean.class),eq(orphan))).thenReturn(false);
        when(jdbc.queryForObject(eq("SELECT EXISTS(SELECT 1 FROM generation_cleanup_outbox WHERE generation_id=?)"),eq(Boolean.class),eq(tracked))).thenReturn(true);
        when(jdbc.query(startsWith("SELECT id FROM repositories"),any(org.springframework.jdbc.core.RowMapper.class),eq("acme"),eq("payments")))
                .thenReturn(List.of());
        when(jdbc.update(startsWith("INSERT INTO generation_cleanup_outbox"),eq(orphan),any(),eq("acme"),eq("payments"))).thenReturn(1);
        var zoekt=new ZoektIndexer(null,null) {
            @Override public List<IndexedGeneration> listIndexedGenerations() {
                return List.of(new IndexedGeneration("acme","payments",orphan),
                        new IndexedGeneration("acme","payments",live),
                        new IndexedGeneration("acme","payments",tracked));
            }
            @Override public void removeGeneration(RepositoryRecord repo,UUID generation) {}
        };
        var service=new GenerationRetentionService(jdbc,mock(RepositoryIndexLeaseManager.class),zoekt,mock(PlatformTransactionManager.class),30,10);
        assertEquals(1,service.reconcileOrphanedShards());
        verify(jdbc).update(startsWith("INSERT INTO generation_cleanup_outbox"),eq(orphan),any(),eq("acme"),eq("payments"));
        verify(jdbc,never()).update(startsWith("INSERT INTO generation_cleanup_outbox"),eq(live),any(),any(),any());
        verify(jdbc,never()).update(startsWith("INSERT INTO generation_cleanup_outbox"),eq(tracked),any(),any(),any());
    }
}
