package com.wynk.codecontext.index;

import com.sun.net.httpserver.HttpServer;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MavenHttpPolicyTest {
    @TempDir Path cache;
    @Test void configuredTransportRefusesRedirectsWithoutContactingTheTarget() throws Exception {
        var origins=new AtomicInteger();var redirected=new AtomicInteger();
        var target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var origin=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try {
            target.createContext("/",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});
            origin.createContext("/",exchange->{
                origins.incrementAndGet();
                exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+target.getAddress().getPort()+"/metadata");
                exchange.sendResponseHeaders(302,-1);exchange.close();
            });
            target.start();origin.start();
            var locator=MavenRepositorySystemUtils.newServiceLocator();
            locator.addService(RepositoryConnectorFactory.class,BasicRepositoryConnectorFactory.class);
            locator.addService(TransporterFactory.class,HttpTransporterFactory.class);
            var system=locator.getService(RepositorySystem.class);
            var session=new MavenClasspathResolver().newSession(cache);
            // Raw component transport test only: application RepositorySpec independently rejects HTTP/loopback.
            var repository=new RemoteRepository.Builder("fixture","default","http://127.0.0.1:"+origin.getAddress().getPort()+"/").build();
            var request=new ArtifactRequest().setArtifact(new DefaultArtifact("test","artifact","pom","1")).setRepositories(List.of(repository));
            assertThrows(ArtifactResolutionException.class,()->system.resolveArtifact(session,request));
            assertTrue(origins.get()>0,"test must exercise the actual HTTP transport");
            assertEquals(0,redirected.get(),"redirect destination must not receive any request");
        } finally {origin.stop(0);target.stop(0);}
    }
}
