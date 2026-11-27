package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class IndexingDiagnosticsTest {
    @Test void aPlainInterruptedOrFilesystemFailureIsNotADatabaseFailure() {
        assertEquals("",IndexingService.sqlDiagnosticChain(new InterruptedException("fixture interruption")));
        assertEquals("",IndexingService.sqlDiagnosticChain(new IllegalStateException(new java.io.IOException("fixture git failure"))));
    }
    @Test void preservesActualPostgresStateMessageAndObjectFieldsThroughWrappers() {
        var server=new ServerErrorMessage("SERROR\0C42703\0Mcolumn dependency_version does not exist\0scontext\0tsemantic_call_sites\0cdependency_version\0nfixture_constraint\0\0");
        var sql=new PSQLException(server);
        var diagnostic=IndexingService.sqlDiagnosticChain(new IllegalStateException("wrapper",sql));
        assertTrue(diagnostic.contains("SQLSTATE=42703"),diagnostic);
        assertTrue(diagnostic.contains("message=ERROR: column dependency_version does not exist"),diagnostic);
        for(String field:new String[]{"schema=context","table=semantic_call_sites","column=dependency_version","constraint=fixture_constraint"})assertTrue(diagnostic.contains(field),diagnostic);
    }
    @Test void retainsChainedSqlStatesAndTerminatesACauseCycle() {
        var sql=new SQLException("first","08006");sql.setNextException(new SQLException("next","23503"));
        var wrapper=new IllegalStateException("wrapper",sql);sql.initCause(wrapper);
        var diagnostic=assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->IndexingService.sqlDiagnosticChain(wrapper));
        assertTrue(diagnostic.contains("SQLSTATE=08006"));assertTrue(diagnostic.contains("SQLSTATE=23503"));
    }
}
