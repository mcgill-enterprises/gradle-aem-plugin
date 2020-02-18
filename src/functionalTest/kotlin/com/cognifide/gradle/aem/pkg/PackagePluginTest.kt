package com.cognifide.gradle.aem.pkg
import com.cognifide.gradle.aem.test.AemBuildTest
import org.junit.jupiter.api.Test

class PackagePluginTest: AemBuildTest() {

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

            val pkgPath = "build/packageCompose/package-minimal-1.0.0.zip"

            assertPackage(pkgPath)

            assertZipEntry(pkgPath, "jcr_root/apps/example/.content.xml")

            assertZipEntry(pkgPath, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  
                  <filter root="/apps/example"></filter>
                  
                </workspaceFilter>

            """)

            assertZipEntry(pkgPath, "META-INF/vault/properties.xml", """
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

            // Assembly project

            file("assembly/build.gradle","""
                plugins {
                    id("com.cognifide.aem.package")
                }
                
                group = "com.company.example.aem"
                
                tasks {
                    packageCompose {
                        fromProject(":ui.apps")
                        fromProject(":ui.content")
                    }
                }
            """)

            // UI apps project

            file("ui.apps/build.gradle", """
                plugins {
                    id("com.cognifide.aem.bundle")
                }
                
                group = "com.company.example.aem"
                
                repositories {
                    jcenter()
                }
                
                dependencies {
                    compileOnly("org.slf4j:slf4j-api:1.5.10")
                    compileOnly("org.osgi:osgi.cmpn:6.0.0")
                }
                """)

            file("ui.apps/src/main/content/META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                    <filter root="/apps/example/ui.apps"/>
                </workspaceFilter>
            """)

            file("ui.apps/src/main/java/com/company/example/aem/HelloService.java", """
                package com.company.example.aem;
                
                import org.osgi.service.component.annotations.Activate;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Deactivate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                
                @Component(immediate = true, service = HelloService.class)
                class HelloService {
                                   
                    private static final Logger LOG = LoggerFactory.getLogger(HelloService.class);
                    
                    @Activate
                    protected void activate() {
                        LOG.info("Hello world!");
                    }
                    
                    @Deactivate
                    protected void deactivate() {
                        LOG.info("Good bye world!");
                    }
                }
            """)

            // UI content project

            file("ui.content/build.gradle", """
                plugins {
                    id("com.cognifide.aem.package")
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
        }

        runBuild(projectDir, ":assembly:packageCompose", "-Poffline") {
            assertTask(":assembly:packageCompose")

            val pkgPath = "assembly/build/packageCompose/example-assembly-1.0.0.zip"

            assertPackage(pkgPath)

            // Check if bundle was build in sub-project
            assertBundle("ui.apps/build/bundleCompose/example-ui.apps-1.0.0.jar")
            assertZipEntry("ui.apps/build/bundleCompose/example-ui.apps-1.0.0.jar", "OSGI-INF/com.company.example.aem.HelloService.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.3.0" name="com.company.example.aem.HelloService" immediate="true" activate="activate" deactivate="deactivate">
                  <service>
                    <provide interface="com.company.example.aem.HelloService"/>
                  </service>
                  <implementation class="com.company.example.aem.HelloService"/>
                </scr:component>
            """)

            // Check assembled package
            assertZipEntry(pkgPath, "META-INF/vault/filter.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workspaceFilter version="1.0">
                  
                  <filter root="/apps/example/ui.apps"></filter>
                  
                  <filter root="/content/example"></filter>
                  
                </workspaceFilter>
            """)

            assertZipEntry(pkgPath, "META-INF/vault/properties.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                <properties>
                    
                    <entry key="group">com.company.example.aem</entry>
                    <entry key="name">example-assembly</entry>
                    <entry key="version">1.0.0</entry>
                    
                    <entry key="createdBy">${System.getProperty("user.name")}</entry>
                    
                    
                    <entry key="acHandling">merge_preserve</entry>
                    
                    <entry key="requiresRoot">false</entry>
                    
                </properties>
            """)

            assertZipEntry(pkgPath, "jcr_root/content/example/.content.xml")
            assertZipEntry(pkgPath, "jcr_root/apps/example/ui.apps/install/example-ui.apps-1.0.0.jar")
        }
    }
}