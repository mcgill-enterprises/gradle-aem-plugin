package com.cognifide.gradle.aem.common.tasks

import com.cognifide.gradle.aem.AemDefaultTask
import com.cognifide.gradle.aem.common.instance.Instance
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

open class InstanceTask : AemDefaultTask() {

    @Input
    val instances = aem.obj.list<Instance> {
        convention(aem.obj.provider { aem.instances })
    }

    @get:Internal
    val instanceManager get() = aem.instanceManager
}
