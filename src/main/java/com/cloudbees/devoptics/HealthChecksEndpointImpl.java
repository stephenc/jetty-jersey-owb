package com.cloudbees.devoptics;

import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import java.io.IOException;
import java.io.OutputStream;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

@Path("health")
@ApplicationScoped
public class HealthChecksEndpointImpl {
    @Inject
    private SmallRyeHealthReporter registry;

    @GET
//    @Produces(MediaType.APPLICATION_JSON)
    public Response getChecks() {

        final SmallRyeHealth health = registry.getHealth();
        return Response.status(health.isDown() ? Response.Status.SERVICE_UNAVAILABLE : Response.Status.OK)
                .entity(
                        new StreamingOutput() {

                            @Override
                            public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                                registry.reportHealth(outputStream, health);
                            }
                        }).build();
    }

}
