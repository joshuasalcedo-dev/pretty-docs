package io.joshuasalcedo.pretty.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.CountDownLatch;

/**
 * Serves the generated site on http://localhost:4400/ until interrupted (Ctrl+C).
 * <pre>
 * mvn io.joshuasalcedo.pretty:pretty-docs-maven-plugin:generate io.joshuasalcedo.pretty:pretty-docs-maven-plugin:serve
 * </pre>
 */
@Mojo(name = "serve", aggregator = true, threadSafe = true)
public class ServeMojo extends AbstractMojo {

    /** The generated site directory (the generate goal's output). */
    @Parameter(property = "pretty-docs.outputDirectory", defaultValue = "${project.build.directory}/pretty-docs")
    private File outputDirectory;

    @Parameter(property = "pretty-docs.port", defaultValue = "" + StaticSiteServer.DEFAULT_PORT)
    private int port;

    /** Interface to bind; use 0.0.0.0 to allow other machines to connect. */
    @Parameter(property = "pretty-docs.host", defaultValue = "localhost")
    private String host;

    @Override
    public void execute() throws MojoExecutionException {
        StaticSiteServer server;
        try {
            server = new StaticSiteServer(outputDirectory.toPath(), host, port);
        } catch (BindException e) {
            throw new MojoExecutionException("Port " + port + " is already in use; pick another with -Dpretty-docs.port=<port>", e);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        String url = "http://" + ("0.0.0.0".equals(host) ? "localhost" : host) + ":" + server.port() + "/";
        getLog().info("");
        getLog().info("Serving " + outputDirectory);
        getLog().info("Documentation: " + url);
        getLog().info("Press Ctrl+C to stop.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }
}
