package com.cognifide.gradle.aem.common.tasks

import com.cognifide.gradle.aem.AemDefaultTask
import com.cognifide.gradle.aem.common.instance.LocalInstance
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

open class LocalInstanceTask : AemDefaultTask() {

    @Input
    val instances = aem.obj.list<LocalInstance> {
        convention(aem.obj.provider { aem.localInstances })
    }

    @get:Internal
    val localInstanceManager get() = aem.localInstanceManager
}
