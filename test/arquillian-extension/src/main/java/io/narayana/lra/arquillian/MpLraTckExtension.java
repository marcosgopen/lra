/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian;

import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.core.spi.LoadableExtension;

import io.narayana.lra.arquillian.processor.MpLraTckAuxiliaryArchiveProcessor;

/**
 * This class is the activation point to use {@link MpLraTckAuxiliaryArchiveAppender}.
 */
public class MpLraTckExtension  implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder extensionBuilder) {
        extensionBuilder.service(ApplicationArchiveProcessor.class, MpLraTckAuxiliaryArchiveProcessor.class);
    }
}