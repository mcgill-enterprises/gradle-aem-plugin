package com.cognifide.gradle.aem.pkg
import com.cognifide.gradle.aem.test.AemBuildTest
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import java.io.File

@Suppress("LongMethod", "MaxLineLength")
class PackagePluginTest : AemBuildTest() {

    @Test
    fun `should build package using minimal configuration`() {
        val projectDir = prepareProject("package-minimal") {
            settingsGradle("")

            buildGradle("""
                plugins {
                    id("com.cognifide.aem.package")
                }
                
                group = "com.company.example"
                version = "1.0.0"
                """)

            file("src/main/content/jcr_root/apps/example/.content.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
                    jcr:primaryType="sling:Folder"/>
                """)

            file("src/main/content/META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                    <filter root="/apps/example"/>
                </workspaceFilter>
                """)
        }

        runBuild(projectDir, "packageCompose", "-Poffline") {
            assertTask(":packageCompose")

            val pkg = file("build/packageCompose/package-minimal-1.0.0.zip")

            assertPackage(pkg)

            assertZipEntry(pkg, "jcr_root/apps/example/.content.xml")

            assertZipEntryEquals(pkg, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  <filter root="/apps/example"/>
                  
                </workspaceFilter>
            """)

            assertZipEntryEquals(pkg, "META-INF/vault/properties.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                <properties>
                    
                    <entry key="group">com.company.example</entry>
                    <entry key="name">package-minimal</entry>
                    <entry key="version">1.0.0</entry>
                    
                    <entry key="createdBy">${System.getProperty("user.name")}</entry>
                    
                    <entry key="acHandling">merge_preserve</entry>
                    <entry key="requiresRoot">false</entry>
                    
                </properties>
            """)
        }

        runBuild(projectDir, "packageCompose", "-Poffline") {
            assertTask(":packageCompose", TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `should build assembly package with content and bundles merged`() {
        val projectDir = prepareProject("package-assembly") {
            settingsGradle("""
                rootProject.name = "example"
                
                include("ui.apps")
                include("ui.content") 
                include("assembly")
            """)

            gradleProperties("""
                version=1.0.0
            """)

            file("assembly/build.gradle.kts", """
                plugins {
                    id("com.cognifide.aem.package")
                }
                
                group = "com.company.example.aem"
                
                tasks {
                    packageCompose {
                        mergePackageProject(":ui.apps")
                        mergePackageProject(":ui.content")
                    }
                }
            """)

            uiApps()
            uiContent()
        }

        runBuild(projectDir, ":assembly:packageCompose", "-Poffline") {
            assertTask(":assembly:packageCompose")

            val pkg = file("assembly/build/packageCompose/example-assembly-1.0.0.zip")

            assertPackage(pkg)

            // Check if bundle was build in sub-project
            assertBundle("ui.apps/build/bundleCompose/example-ui.apps-1.0.0.jar")
            assertZipEntryEquals("ui.apps/build/bundleCompose/example-ui.apps-1.0.0.jar", "OSGI-INF/com.company.example.aem.HelloService.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.3.0" name="com.company.example.aem.HelloService" immediate="true" activate="activate" deactivate="deactivate">
                  <service>
                    <provide interface="com.company.example.aem.HelloService"/>
                  </service>
                  <implementation class="com.company.example.aem.HelloService"/>
                </scr:component>
            """)

            // Check assembled package
            assertZipEntryEquals(pkg, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  <filter root="/apps/example/ui.apps"/>
                  <filter root="/content/example"/>
                  
                </workspaceFilter>
            """)

            assertZipEntryEquals(pkg, "META-INF/vault/properties.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                <properties>
                    
                    <entry key="group">com.company.example.aem</entry>
                    <entry key="name">example-assembly</entry>
                    <entry key="version">1.0.0</entry>
                    
                    <entry key="createdBy">${System.getProperty("user.name")}</entry>
                    
                    <entry key="acHandling">merge_preserve</entry>
                    <entry key="requiresRoot">false</entry>
                    <entry key="installhook.actool.class">biz.netcentric.cq.tools.actool.installhook.AcToolInstallHook</entry>
                    <entry key="ui.apps.merged">test</entry>
                    <entry key="installhook.aecu.class">de.valtech.aecu.core.installhook.AecuInstallHook</entry>
                    
                </properties>
            """)

            assertZipEntry(pkg, "jcr_root/content/example/.content.xml")
            assertZipEntry(pkg, "jcr_root/apps/example/ui.apps/install/example-ui.apps-1.0.0.jar")
            assertZipEntryMatching(pkg, "META-INF/vault/nodetypes.cnd", """
                <'apps'='http://apps.com/apps/1.0'>
                <'content'='http://content.com/content/1.0'>
                *
                [apps:Folder] > nt:folder
                  - * (undefined) multiple
                  - * (undefined)
                  + * (nt:base) = apps:Folder version
                *
                [content:Folder] > nt:folder
                  - * (undefined) multiple
                  - * (undefined)
                  + * (nt:base) = content:Folder version
            """)
        }

        runBuild(projectDir, ":assembly:packageCompose", "-Poffline") {
            assertTask(":assembly:packageCompose", TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `should build package with nested bundle and subpackages from Maven repository`() {
        val projectDir = prepareProject("package-nesting-repository") {
            settingsGradle("")

            buildGradle("""
                plugins {
                    id("com.cognifide.aem.package")
                }
                
                group = "com.company.example"
                version = "1.0.0"
                
                repositories {
                    jcenter()
                }
                
                tasks {
                    packageCompose {
                        installBundle("org.jsoup:jsoup:1.10.2")
                        installBundle("com.github.mickleroy:aem-sass-compiler:1.0.1")
                        installBundle("com.neva.felix:search-webconsole-plugin:1.3.0") { runMode.set("author") }
                        
                        nestPackage("com.adobe.cq:core.wcm.components.all:2.8.0")
                        nestPackage("com.adobe.cq:core.wcm.components.examples:2.8.0")
                    }
                }
                """)

            defaultPlanJson()
        }

        runBuild(projectDir, "packageCompose", "-Poffline") {
            assertTask(":packageCompose")

            val pkg = file("build/packageCompose/package-nesting-repository-1.0.0.zip")

            assertPackage(pkg)

            assertZipEntry(pkg, "jcr_root/apps/package-nesting-repository/install/jsoup-1.10.2.jar")
            assertZipEntry(pkg, "jcr_root/apps/package-nesting-repository/install/aem-sass-compiler-1.0.1.jar")
            assertZipEntry(pkg, "jcr_root/apps/package-nesting-repository/install.author/search-webconsole-plugin-1.3.0.jar")
            assertZipEntry(pkg, "jcr_root/etc/packages/adobe/cq60/core.wcm.components.all-2.8.0.zip")
            assertZipEntry(pkg, "jcr_root/etc/packages/adobe/cq60/core.wcm.components.examples-2.8.0.zip")

            assertZipEntryEquals(pkg, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  <filter root="/apps/package-nesting-repository/install/jsoup-1.10.2.jar"/>
                  <filter root="/apps/package-nesting-repository/install/aem-sass-compiler-1.0.1.jar"/>
                  <filter root="/apps/package-nesting-repository/install.author/search-webconsole-plugin-1.3.0.jar"/>
                  <filter root="/etc/packages/adobe/cq60/core.wcm.components.all-2.8.0.zip"/>
                  <filter root="/etc/packages/adobe/cq60/core.wcm.components.examples-2.8.0.zip"/>
                  
                </workspaceFilter>
            """)
        }

        runBuild(projectDir, "packageCompose", "-Poffline") {
            assertTask(":packageCompose", TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `should build package with nested bundle and sub-package built by sub-projects`() {
        val projectDir = prepareProject("package-nesting-built") {
            settingsGradle("""
                rootProject.name = "example"
                
                include("ui.apps")
                include("ui.content") 
            """)

            gradleProperties("""
                version=1.0.0
            """)

            buildGradle("""
                plugins {
                    id("com.cognifide.aem.package")
                }
                
                group = "com.company.example"
                
                repositories {
                    jcenter()
                }
                
                tasks {
                    packageCompose {
                        nestPackageBuilt(":ui.content:packageCompose")
                        installBundleBuilt(":ui.apps:bundleCompose")
                    }
                }
                """)

            defaultPlanJson()

            uiApps()
            uiContent()
        }

        runBuild(projectDir, "packageCompose", "-Poffline") {
            assertTask(":packageCompose")

            val pkg = file("build/packageCompose/example-1.0.0.zip")

            assertPackage(pkg)

            assertZipEntry(pkg, "jcr_root/apps/example/ui.apps/install/example-ui.apps-1.0.0.jar")
            assertZipEntry(pkg, "jcr_root/etc/packages/com.company.example/example-ui.content-1.0.0.zip")

            assertZipEntryEquals(pkg, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  <filter root="/apps/example/ui.apps/install/example-ui.apps-1.0.0.jar"/>
                  <filter root="/etc/packages/com.company.example/example-ui.content-1.0.0.zip"/>
                  
                </workspaceFilter>
            """)
        }
    }

    private fun File.uiApps() {
        file("ui.apps/build.gradle.kts", """
            plugins {
                id("com.cognifide.aem.bundle")
                id("com.cognifide.aem.package")
            }
            
            group = "com.company.example.aem"
            
            repositories {
                jcenter()
            }
            
            dependencies {
                compileOnly("org.slf4j:slf4j-api:1.5.10")
                compileOnly("org.osgi:osgi.cmpn:6.0.0")
            }
            
            tasks {
                packageCompose {
                    vaultDefinition {
                        property("installhook.actool.class", "biz.netcentric.cq.tools.actool.installhook.AcToolInstallHook")
                    }
                    merged { assembly ->
                        assembly.vaultDefinition.property("ui.apps.merged", "test")
                    }
                }
            }
            """)

        file("ui.apps/src/main/content/META-INF/vault/nodetypes.cnd", """
            <'apps'='http://apps.com/apps/1.0'>

            [apps:Folder] > nt:folder
              - * (undefined) multiple
              - * (undefined)
              + * (nt:base) = apps:Folder version
        """)

        file("ui.apps/src/main/content/META-INF/vault/filter.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <workspaceFilter version="1.0">
                <filter root="/apps/example/ui.apps"/>
            </workspaceFilter>
        """)

        helloServiceJava("ui.apps")
    }

    private fun File.uiContent() {
        file("ui.content/build.gradle.kts", """
            plugins {
                id("com.cognifide.aem.package")
            }
            
            group = "com.company.example"
            
            tasks {
                packageCompose {
                    vaultDefinition {
                        property("installhook.aecu.class", "de.valtech.aecu.core.installhook.AecuInstallHook")
                    }
                }
            }
        """)

        file("ui.content/src/main/content/jcr_root/content/example/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
                jcr:primaryType="sling:Folder"/>
        """)

        file("ui.content/src/main/content/META-INF/vault/filter.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <workspaceFilter version="1.0">
                <filter root="/content/example"/>
            </workspaceFilter>
        """)

        file("ui.content/src/main/content/META-INF/vault/nodetypes.cnd", """
            <'content'='http://content.com/content/1.0'>

            [content:Folder] > nt:folder
              - * (undefined) multiple
              - * (undefined)
              + * (nt:base) = content:Folder version
        """)
    }

    private fun File.defaultPlanJson() {
        file("src/aem/package/OAKPAL_OPEAR/default-plan.json", """
                {
                  "checklists": [
                    "net.adamcin.oakpal.core/basic"
                  ],
                  "installHookPolicy": "SKIP",
                  "checks": [
                    {
                      "name": "basic/subpackages",
                      "config": {
                        "denyAll": false
                      }
                    }
                  ]
                } 
            """)
    }
}
