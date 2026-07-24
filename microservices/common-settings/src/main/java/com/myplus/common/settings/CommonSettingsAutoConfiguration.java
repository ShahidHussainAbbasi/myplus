package com.myplus.common.settings;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Wires the shared settings engine + API into any servlet service that (a) has this module on its classpath
 * and (b) supplies a {@link SettingsStore} bean (its own table-backed impl). Without a store, nothing is
 * registered — so the module is inert until a service opts in. Registered via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (not component-scanned),
 * so services need no @ComponentScan/@EntityScan change.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(SettingsStore.class)
@Import({ SettingsService.class, SettingsController.class })
public class CommonSettingsAutoConfiguration {
}
