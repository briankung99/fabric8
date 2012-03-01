/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.fabric.itests.paxexam;

import java.io.File;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import javax.inject.Inject;

import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.CreateContainerMetadata;
import org.fusesource.fabric.api.CreateContainerOptions;
import org.fusesource.fabric.api.CreateContainerOptionsBuilder;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.Profile;
import org.fusesource.fabric.api.Version;
import org.fusesource.fabric.internal.ZooKeeperUtils;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.linkedin.zookeeper.client.IZKClient;
import org.linkedin.zookeeper.client.ZKClient;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.BundleStartLevelOption;
import org.ops4j.pax.exam.options.DefaultCompositeOption;
import org.ops4j.pax.exam.options.MavenArtifactProvisionOption;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.debugConfiguration;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.CoreOptions.bundleStartLevel;
import static org.ops4j.pax.exam.CoreOptions.excludeDefaultRepositories;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.profile;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.useOwnExamBundlesStartLevel;

public class FabricTestSupport {

    public static final Long DEFAULT_TIMEOUT = 10000L;
    public static final Long SYSTEM_TIMEOUT = 30000L;
    public static final Long DEFAULT_WAIT = 10000L;
    public static final Long PROVISION_TIMEOUT = 120000L;

    static final String GROUP_ID = "org.fusesource.fabric";
    static final String ARTIFACT_ID = "fuse-fabric";

    static final String KARAF_GROUP_ID = "org.apache.karaf";
    static final String KARAF_ARTIFACT_ID = "apache-karaf";

    @Inject
    protected BundleContext bundleContext;

    /**
     * Creates a child {@ling Agent} witht the given name.
     *
     *
     * @param name The name of the child {@ling Agent}.
     * @param parent
     * @return
     */
    protected Container createChildContainer(String name, String parent, String profileName) throws Exception {
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        Thread.sleep(DEFAULT_WAIT);

        Container parentContainer = fabricService.getContainer(parent);
        assertNotNull(parentContainer);


        CreateContainerOptions args = CreateContainerOptionsBuilder.child().name(name).parent(parent);
        CreateContainerMetadata[] metadata = fabricService.createContainers(args);
        if (metadata.length > 0) {
            if (metadata[0].getFailure() != null) {
                throw new Exception("Error creating child container:"+name, metadata[0].getFailure());
            }
            Container container =  metadata[0].getContainer();
            Version version = fabricService.getDefaultVersion();
            Profile profile  = fabricService.getProfile(version.getName(),profileName);
            assertNotNull("Expected to find profile with name:" + profileName,profile);
            container.setProfiles(new Profile[]{profile});
            waitForProvisionSuccess(container, PROVISION_TIMEOUT);
            return container;
        }
        throw new Exception("Could container not created");
    }

    protected void destroyChildContainer(String name) throws InterruptedException {
        try {
            //Wait for zookeeper service to become available.
            IZKClient zooKeeper = getOsgiService(IZKClient.class);

            FabricService fabricService = getOsgiService(FabricService.class);
            assertNotNull(fabricService);

            Thread.sleep(DEFAULT_WAIT);
            Container container = fabricService.getContainer(name);
            container.destroy();
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
        }
    }


    /**
     * Waits for a container to successfully provision.
     * @param container
     * @param timeout
     * @throws Exception
     */
    public void waitForProvisionSuccess(Container container, Long timeout) throws Exception {
        System.err.println("Waiting for container: " + container.getId() + " to succesfully provision");
        for (long t = 0; (!(container.isAlive() && container.getProvisionStatus().equals("success") && container.getSshUrl() != null) && t < timeout); t += 2000L) {
            if (container.getProvisionException() != null) {
                throw new Exception(container.getProvisionException());
            }
            Thread.sleep(2000L);
            System.err.println("Alive:"+container.isAlive()+" Status:"+ container.getProvisionStatus()+" SSH URL:" + container.getSshUrl());
        }
        if (!container.isAlive() || !container.getProvisionStatus().equals("success") ||  container.getSshUrl() == null) {
            throw new Exception("Could not provision " + container.getId() + " after " + timeout + " seconds. Alive:"+ container.isAlive()+" Status:"+container.getProvisionStatus()+" Ssh URL:"+container.getSshUrl());
        }
    }


    /**
     * Creates a child container, waits for succesfull provisioning and asserts, its asigned the right profile.
     * @param name
     * @param parent
     * @param profile
     * @throws Exception
     */
    public void createAndAssetChildContainer(String name, String parent, String profile) throws Exception {
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        Container child1 = createChildContainer(name, parent, profile);
        Container result = fabricService.getContainer(name);
        assertEquals("Containers should have the same id", child1.getId(), result.getId());
    }

    /**
     * Cleans a containers profile by switching to default profile and reseting the profile.
     * @param containerName
     * @param profileName
     * @throws Exception
     */
    public boolean containerSetProfile(String containerName, String profileName) throws Exception {
        System.out.println("Switching profile: "+profileName+" on container:"+containerName);
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        IZKClient zookeeper = getOsgiService(IZKClient.class);
        assertNotNull(zookeeper);

        Container container = fabricService.getContainer(containerName);
        Version version = container.getVersion();
        Profile[] profiles = new Profile[]{fabricService.getProfile(version.getName(),profileName)};
        Profile[] currentProfiles = container.getProfiles();

        Arrays.sort(profiles);
        Arrays.sort(currentProfiles);

        boolean same = true;
        if (profiles.length != currentProfiles.length) {
            same = false;
        } else {
            for (int i = 0; i < currentProfiles.length; i++) {
                if (!currentProfiles[i].configurationEquals(profiles[i])) {
                    same = false;
                }
            }
        }

        if (!same) {
            //This is required so that waitForProvisionSuccess doesn't retrun before the deployment agent kicks in.
            ZooKeeperUtils.set(zookeeper, ZkPath.CONTAINER_PROVISION_RESULT.getPath(containerName), "switching profile");
            container.setProfiles(profiles);
            waitForProvisionSuccess(container, PROVISION_TIMEOUT);
        }
        return same;
    }



    /**
     * Returns the Version of Karaf to be used.
     *
     * @return
     */
    protected String getKarafVersion() {
        //TODO: This is a hack because pax-exam-karaf will not work with non numeric characters in the version.
        //We will need to change it once pax-exam-karaf get fixed (version 0.4.0 +).
        return "2.2.5";
    }

    /**
     * Create an {@link Option} for using a Fabric distribution.
     *
     * @return
     */
    protected Option fabricDistributionConfiguration() {
        return new DefaultCompositeOption(
                new Option[]{karafDistributionConfiguration().frameworkUrl(
                        maven().groupId(GROUP_ID).artifactId(ARTIFACT_ID).versionAsInProject().type("tar.gz"))
                        .karafVersion(getKarafVersion()).name("Fabric Karaf Distro").unpackDirectory(new File("target/paxexam/unpack/")),
                        useOwnExamBundlesStartLevel(40),
                      editConfigurationFilePut("etc/config.properties", "karaf.startlevel.bundle", "35")
                });
    }

    protected Bundle installBundle(String groupId, String artifactId) throws Exception {
        MavenArtifactProvisionOption mvnUrl = mavenBundle(groupId, artifactId);
        return bundleContext.installBundle(mvnUrl.getURL());
    }


    protected Bundle getInstalledBundle(String symbolicName) {
        for (Bundle b : bundleContext.getBundles()) {
            if (b.getSymbolicName().equals(symbolicName)) {
                return b;
            }
        }
        for (Bundle b : bundleContext.getBundles()) {
            System.err.println("Bundle: " + b.getSymbolicName());
        }
        throw new RuntimeException("Bundle " + symbolicName + " does not exist");
    }

    /*
    * Explode the dictionary into a ,-delimited list of key=value pairs
    */
    private static String explode(Dictionary dictionary) {
        Enumeration keys = dictionary.keys();
        StringBuffer result = new StringBuffer();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            result.append(String.format("%s=%s", key, dictionary.get(key)));
            if (keys.hasMoreElements()) {
                result.append(", ");
            }
        }
        return result.toString();
    }

    protected <T> T getOsgiService(Class<T> type, long timeout) {
        return getOsgiService(type, null, timeout);
    }

    protected <T> T getOsgiService(Class<T> type) {
        return getOsgiService(type, null, DEFAULT_TIMEOUT);
    }

    protected <T> T getOsgiService(Class<T> type, String filter, long timeout) {
        ServiceTracker tracker = null;
        try {
            String flt;
            if (filter != null) {
                if (filter.startsWith("(")) {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")" + filter + ")";
                } else {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")(" + filter + "))";
                }
            } else {
                flt = "(" + Constants.OBJECTCLASS + "=" + type.getName() + ")";
            }
            Filter osgiFilter = FrameworkUtil.createFilter(flt);
            tracker = new ServiceTracker(bundleContext, osgiFilter, null);
            tracker.open(true);
            // Note that the tracker is not closed to keep the reference
            // This is buggy, as the service reference may change i think
            Object svc = type.cast(tracker.waitForService(timeout));
            if (svc == null) {
                Dictionary dic = bundleContext.getBundle().getHeaders();
                System.err.println("Test bundle headers: " + explode(dic));

                for (ServiceReference ref : asCollection(bundleContext.getAllServiceReferences(null, null))) {
                    System.err.println("ServiceReference: " + ref);
                }

                for (ServiceReference ref : asCollection(bundleContext.getAllServiceReferences(null, flt))) {
                    System.err.println("Filtered ServiceReference: " + ref);
                }

                throw new RuntimeException("Gave up waiting for service " + flt);
            }
            return type.cast(svc);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid filter", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Make available system properties that are configured for the test, to the test container.
     * <p>Note:</p> If not obvious the container runs in in forked mode and thus system properties passed
     * form command line or surefire plugin are not available to the container without an approach like this.
     * @param propertyName
     * @return
     */
    public static Option copySystemProperty(String propertyName) {
        return editConfigurationFilePut("etc/system.properties", propertyName, System.getProperty(propertyName) != null ? System.getProperty(propertyName) : "");
    }

    /*
     * Provides an iterable collection of references, even if the original array is null
     */
    private static Collection<ServiceReference> asCollection(ServiceReference[] references) {
        return references != null ? Arrays.asList(references) : Collections.<ServiceReference>emptyList();
    }

    /**
     * Create an provisioning option for the specified maven artifact
     * (groupId and artifactId), using the version found in the list
     * of dependencies of this maven project.
     *
     * @param groupId    the groupId of the maven bundle
     * @param artifactId the artifactId of the maven bundle
     * @return the provisioning option for the given bundle
     */
    protected static MavenArtifactProvisionOption mavenBundle(String groupId, String artifactId) {
        return CoreOptions.mavenBundle(groupId, artifactId).versionAsInProject();
    }

    /**
     * Create an provisioning option for the specified maven artifact
     * (groupId and artifactId), using the version found in the list
     * of dependencies of this maven project.
     *
     * @param groupId    the groupId of the maven bundle
     * @param artifactId the artifactId of the maven bundle
     * @param version    the version of the maven bundle
     * @return the provisioning option for the given bundle
     */
    protected static MavenArtifactProvisionOption mavenBundle(String groupId, String artifactId, String version) {
        return CoreOptions.mavenBundle(groupId, artifactId).version(version);
    }
}
