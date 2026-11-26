package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.GenerationRetentionService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import com.wynk.codecontext.model.RepositoryRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RepositoryControllerLifecycleTest {
    @Test void softDisableRequiresAdminAndPreservesHistoryFlag() throws Exception {
        UUID id=UUID.randomUUID();
        var repo=new RepositoryRecord(id,"acme","payments","https://github.com/acme/payments.git","platform","main","SERVICE","/repos/acme/payments");
        AtomicBoolean disabled=new AtomicBoolean();
        var store=new RepositoryStore(new JdbcTemplate()) {
            @Override public Optional<RepositoryRecord> find(String owner,String name){return Optional.of(repo);}
            @Override public Optional<RepositoryRecord> findAny(String owner,String name){return Optional.of(repo);}
            @Override public void disable(UUID repositoryId){assertEquals(id,repositoryId);disabled.set(true);}
        };
        var controller=controller(store,null,null,null);
        var teamOnly=new UsernamePasswordAuthenticationToken("orders","",List.of(new SimpleGrantedAuthority("ROLE_TEAM")));
        assertThrows(AccessDeniedException.class,()->controller.disable("acme","payments",false,teamOnly));
        var admin=new UsernamePasswordAuthenticationToken("platform","",
                List.of(new SimpleGrantedAuthority("ROLE_TEAM"),new SimpleGrantedAuthority("ROLE_ADMIN")));
        Map<String,Object> response=controller.disable("acme","payments",false,admin);
        assertTrue(disabled.get());
        assertEquals("DISABLED",response.get("status"));
        assertEquals(true,response.get("historyPreserved"));
    }

    @Test void hardPurgeRequiresAdminAndRejectsBusyLease() throws Exception {
        UUID id=UUID.randomUUID();
        var repo=new RepositoryRecord(id,"acme","payments","https://github.com/acme/payments.git","platform","main","SERVICE","/repos/acme/payments");
        var store=new RepositoryStore(new JdbcTemplate()) {
            @Override public Optional<RepositoryRecord> findAny(String owner,String name){return Optional.of(repo);}
        };
        var leases=mock(RepositoryIndexLeaseManager.class);
        when(leases.tryAcquire(eq(id),any())).thenReturn(Optional.empty());
        var controller=controller(store,null,leases,null);
        var teamOnly=new UsernamePasswordAuthenticationToken("orders","",List.of(new SimpleGrantedAuthority("ROLE_TEAM")));
        assertThrows(AccessDeniedException.class,()->controller.disable("acme","payments",true,teamOnly));
        var admin=new UsernamePasswordAuthenticationToken("platform","",
                List.of(new SimpleGrantedAuthority("ROLE_TEAM"),new SimpleGrantedAuthority("ROLE_ADMIN")));
        var busy=assertThrows(IllegalStateException.class,()->controller.disable("acme","payments",true,admin));
        assertTrue(busy.getMessage().contains("lease"));
    }

    @Test void hardPurgeDeletesMirrorAfterDurableDatabasePurge() throws Exception {
        UUID id=UUID.randomUUID();
        var repo=new RepositoryRecord(id,"acme","payments","https://github.com/acme/payments.git","platform","main","SERVICE","/repos/acme/payments");
        AtomicInteger purged=new AtomicInteger();
        AtomicBoolean mirrorDeleted=new AtomicBoolean();
        AtomicBoolean metadataRemoved=new AtomicBoolean();
        AtomicBoolean fenced=new AtomicBoolean();
        AtomicReference<AutoCloseable> leaseHolder=new AtomicReference<>();
        var store=new RepositoryStore(new JdbcTemplate()) {
            @Override public Optional<RepositoryRecord> findAny(String owner,String name){return Optional.of(repo);}
            @Override public int purge(UUID repositoryId){assertEquals(id,repositoryId);assertTrue(fenced.get());purged.set(2);return 2;}
        };
        var git=new GitRepositoryManager(props()) {
            @Override public void deleteMirror(RepositoryRecord record){assertEquals(repo,record);mirrorDeleted.set(true);}
        };
        var leases=mock(RepositoryIndexLeaseManager.class);
        when(leases.tryAcquire(eq(id),any())).thenAnswer(inv->{
            var lease=mock(RepositoryIndexLeaseManager.Lease.class);
            doAnswer(a->{fenced.set(true);return null;}).when(lease).fenceTransaction();
            doAnswer(a->{leaseHolder.set(lease);return null;}).when(lease).close();
            return Optional.of(lease);
        });
        var retention=mock(GenerationRetentionService.class);
        when(retention.cleanOne()).thenReturn(true,false);
        doAnswer(inv->{metadataRemoved.set(true);return null;}).when(retention).removeRepositoryMetadata(id);
        var controller=controller(store,git,leases,retention);
        var admin=new UsernamePasswordAuthenticationToken("platform","",
                List.of(new SimpleGrantedAuthority("ROLE_TEAM"),new SimpleGrantedAuthority("ROLE_ADMIN")));
        Map<String,Object> response=controller.disable("acme","payments",true,admin);
        assertEquals(2,purged.get());
        assertTrue(mirrorDeleted.get());
        assertTrue(metadataRemoved.get());
        assertEquals("PURGED",response.get("status"));
        assertEquals(false,response.get("historyPreserved"));
        assertEquals(2,response.get("generationsQueued"));
        assertNotNull(leaseHolder.get());
    }

    private static RepositoryController controller(RepositoryStore store,GitRepositoryManager git,
                                                   RepositoryIndexLeaseManager leases,GenerationRetentionService retention) {
        var syncTx=new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object transaction,TransactionDefinition definition){}
            @Override protected void doCommit(DefaultTransactionStatus status){}
            @Override protected void doRollback(DefaultTransactionStatus status){}
        };
        return new RepositoryController(store,props(),new JdbcTemplate(),
                git==null?new GitRepositoryManager(props()):git,
                leases==null?mock(RepositoryIndexLeaseManager.class):leases,
                retention==null?mock(GenerationRetentionService.class):retention,
                syncTx,mock(RepositoryOnboarding.class),mock(RepositoryLifecycle.class));
    }
    private static CodeContextProperties props() {
        return new CodeContextProperties("/tmp/repos","/tmp/local","/tmp/zoekt","t=t","a=a","","","github.com","http://localhost",
                1024,1024,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify",false));
    }
}
