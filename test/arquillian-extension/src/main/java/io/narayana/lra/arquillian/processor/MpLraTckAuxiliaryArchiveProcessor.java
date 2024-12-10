/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.processor;

import java.io.File;

import org.eclipse.microprofile.lra.tck.service.spi.LRARecoveryService;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.arquillian.spi.NarayanaLRARecovery;

/**
 * <p>
 * As the MicroProfile LRA TCK is implementation agnostic, deployments created
 * within the TCK must be enriched with dependencies, services and other needed
 * properties/files in order to run the Narayana implementation of the
 * MicroProfile LRA specification. This class is an ad-hoc
 * AuxiliaryArchiveAppender developed exactly for this purpose and it can be
 * activated specifying the activation class
 * {@link io.narayana.lra.arquillian.MpLraTckExtension} in
 * src/main/resources/META-INF/services/org.jboss.arquillian.core.spi.LoadableExtension.
 * Moreover, this extension is activated when an extension section is defined in
 * arquillian.xml of the module
 * </p>
 * <p>
 * To activate this extension in your arquillian.xml, use the following
 * construct:
 * </p>
 * <p>
 * {@code <extension qualifier="MpLraTckAppender"></extension>}
 * </p>
 */
public class MpLraTckAuxiliaryArchiveProcessor implements ApplicationArchiveProcessor {

    @Override
    public void process(Archive<?> archive, TestClass testClass) {
        if (archive instanceof WebArchive) {
            JavaArchive extensionsJar = ShrinkWrap.create(JavaArchive.class, "extension.jar");
            extensionsJar.addClasses(NarayanaLRARecovery.class, LRAConstants.class);
            extensionsJar.addAsServiceProvider(LRARecoveryService.class, NarayanaLRARecovery.class);
            extensionsJar.addAsManifestResource(new StringAsset("<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n"
                    + "       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                    + "       xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd\"\n"
                    + "       version=\"4.0\" bean-discovery-mode=\"all\">\n" + "</beans>"), "beans.xml");
            WebArchive war = (WebArchive) archive;
            war.addAsLibraries(extensionsJar);

            final File archiveDir = new File("target/archives");
            archiveDir.mkdirs();
            File moduleFile = new File(archiveDir, "test-lra-extension.war");
            war.as(ZipExporter.class).exportTo(moduleFile, true);
        }
    }
}