/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.integtests.resolve.suppliers

import org.gradle.integtests.fixtures.GradleMetadataResolveRunner
import org.gradle.integtests.fixtures.RequiredFeature
import org.gradle.integtests.fixtures.RequiredFeatures
import org.gradle.integtests.resolve.AbstractModuleDependencyResolveTest
import org.gradle.test.fixtures.HttpModule
import org.gradle.test.fixtures.server.http.IvyHttpModule
import org.gradle.test.fixtures.server.http.MavenHttpModule

@RequiredFeatures([
    // we only need to check without experimental, it doesn't depend on this flag
    @RequiredFeature(feature = GradleMetadataResolveRunner.EXPERIMENTAL_RESOLVE_BEHAVIOR, value = "false"),
])
class DynamicRevisionRemoteResolveWithMetadataSupplierIntegrationTest extends AbstractModuleDependencyResolveTest {

    def setup() {
        buildFile << """
          import javax.inject.Inject
     
          if (project.hasProperty('refreshDynamicVersions')) {
                configurations.all {
                    resolutionStrategy.cacheDynamicVersionsFor 0, "seconds"
                }
          }
          
          dependencies {
              conf group: "group", name: "projectA", version: "1.+"
              conf group: "group", name: "projectB", version: "latest.release"
          }
          """

        repository {
            'group:projectA' {
                '1.1'()
                '1.2'()
                '2.0'()
            }
            'group:projectB' {
                '1.1'()
                '2.2'()
            }
        }

    }

    def "can use a custom metadata provider"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')

                    }
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputContains 'Providing metadata for group:projectB:1.1'
        !output.contains('Providing metadata for group:projectA:1.1')

        and: "creates a new instance of rule each time"
        !output.contains('Metadata rule call count: 2')
    }

    def "re-executing in subsequent build requires no GET request"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
            }
            'group:projectB' {
                expectVersionListing()
            }
            'group:projectB:1.1' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'release')
                }
                expectResolve()
            }
            'group:projectB:2.2' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'integration')
                }
            }
            'group:projectA:1.2' {
                expectResolve()
            }
        }

        then:
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        and: "re-execute the same build"
        resetExpectations()
        // the two HEAD request below document the existing behavior, not the expected one
        // In particular once we introduce external resource cache policy there shouldn't be any request at all
        supplierInteractions.refresh('group:projectB:1.1')
        supplierInteractions.refresh('group:projectB:2.2')
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

    }

    def "publishing new integration version incurs get status file of new integration version only"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
            }
            'group:projectB' {
                expectVersionListing()
            }
            'group:projectB:1.1' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'release')
                }
                expectResolve()
            }
            'group:projectB:2.2' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'integration')
                }
            }
            'group:projectA:1.2' {
                expectResolve()
            }
        }

        then:
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        when: "publish a new integration version"
        resetExpectations()
        repository {
            'group:projectB:2.3'()
        }
        executer.withArgument('-PrefreshDynamicVersions')

        then:
        supplierInteractions.refresh('group:projectB:1.1', 'group:projectB:2.2')
        repositoryInteractions {
            'group:projectA' {
                expectHeadVersionListing()
            }
            'group:projectB' {
                if (GradleMetadataResolveRunner.useMaven()) {
                    expectHeadVersionListing()
                }
                expectVersionListing()
            }
            'group:projectB:2.3' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'integration')
                }
            }
        }
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
    }

    def "publishing new release version incurs get status file of new release version only"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectB:1.1' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'release')
                }
                expectResolve()
            }
            'group:projectB:2.2' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'integration')
                }
            }
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
            }
        }

        then:
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        when: "publish a new integration version"
        resetExpectations()
        repository {
            'group:projectB:2.3'()
        }
        executer.withArgument('-PrefreshDynamicVersions')

        then:
        repositoryInteractions {
            'group:projectA' {
                expectHeadVersionListing()
            }
            'group:projectB' {
                if (GradleMetadataResolveRunner.useMaven()) {
                    expectHeadVersionListing()
                }
                expectVersionListing()
                '2.3' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')
                    }
                    expectResolve()
                }
            }
        }
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:2.3"
    }

    def "can use --offline to use cached result after remote failure"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
            }
            'group:projectB:1.1' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'release')
                }
                expectResolve()
            }
            'group:projectB:2.2' {
                withModule {
                    supplierInteractions.expectGetStatus(delegate, 'integration')
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        when:
        server.expectHeadBroken('/repo/group/projectB/2.2/status.txt')

        then:
        fails 'checkDeps'

        and:
        failure.assertHasCause("Could not HEAD '${server.uri}/repo/group/projectB/2.2/status.txt'.")

        when:
        resetExpectations()
        executer.withArgument('--offline')

        then: "will used cached status resources"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
    }

    def "can recover from --offline mode"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        executer.withArgument('--offline')

        then:
        fails 'checkDeps'
        failure.assertHasCause("Could not resolve group:projectA:1.+.")
        failure.assertHasCause("No cached version listing for group:projectA:1.+ available for offline mode.")
        failure.assertHasCause("Could not resolve group:projectB:latest.release.")
        failure.assertHasCause("No cached version listing for group:projectB:latest.release available for offline mode.")

        when:
        repositoryInteractions {
            'group:projectB' {
                expectVersionListing()
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')
                    }
                    expectResolve()
                }
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
            }
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
        }

        then: "recovers from previous --offline mode"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
    }

    def "will not make network requests when run with --offline"() {
        given:
        buildFile << """
          // Requests a different resource when offline, to ensure uncached
          if (project.gradle.startParameter.offline) {
             MP.filename = 'status-offline.txt'
          }
          class MP implements ComponentMetadataSupplier {
            final RepositoryResourceAccessor repositoryResourceAccessor
            
            @Inject
            MP(RepositoryResourceAccessor accessor) { repositoryResourceAccessor = accessor }
          
            static String filename = 'status.txt'
          
            void execute(ComponentMetadataSupplierDetails details) {
                def id = details.id
                repositoryResourceAccessor.withResource("\${id.group}/\${id.module}/\${id.version}/\${filename}") {
                    details.result.status = new String(it.bytes)
                }
            }
          }
"""
        metadataSupplierClass = 'MP'
        def supplierInteractions = new SimpleSupplierInteractions()

        when: "Resolves when online"
        repositoryInteractions {
            'group:projectB' {
                expectVersionListing()
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')
                    }
                    expectResolve()
                }
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
            }
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
        }

        then:
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        when: "Fails without making network request when offline"
        resetExpectations()
        executer.withArgument('--offline')

        then:
        fails 'checkDeps'

        failure.assertHasCause("No cached resource '${server.uri}/repo/group/projectB/2.2/status-offline.txt' available for offline mode.")
    }

    def "reports and recovers from remote failure"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectGetMetadata()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration', true)
                    }
                }
            }
        }

        then:
        fails 'checkDeps'
        failure.assertHasCause("Could not resolve group:projectB:latest.release.")
        failure.assertHasCause("Could not GET '${server.uri}/repo/group/projectB/2.2/status.txt'. Received status code 500 from server: broken")

        when:
        resetExpectations()
        repositoryInteractions {
            'group:projectB' {
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')
                    }
                    expectResolve()
                }
            }
            'group:projectA:1.2' {
                expectGetArtifact()
            }
        }

        then: "recovers from previous failure to get status file"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
    }

    def "can inject configuration into metadata provider"() {
        given:
        buildFile << """
          class MP implements ComponentMetadataSupplier {
            String status

            @Inject
            MP(String status) { this.status = status }
            
            void execute(ComponentMetadataSupplierDetails details) {
                if (details.id.version == "2.2") {
                    details.result.status = status
                } else {
                    details.result.status = "release"
                }
            }
          }
          def status = project.findProperty("status") ?: "release"
"""
        setMetadataSupplierClassWithParams('MP', 'status')

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2' {
                    expectResolve()
                }
            }
        }

        then:
        succeeds 'checkDeps'

        when:
        resetExpectations()
        repositoryInteractions {
            'group:projectB:1.1' {
                expectResolve()
            }
        }

        then:
        executer.withArgument("-Pstatus=integration")
        succeeds 'checkDeps'
    }

    def "handles and recovers from errors in a custom metadata provider"() {
        given:
        buildFile << """
          class MP implements ComponentMetadataSupplier {
            void execute(ComponentMetadataSupplierDetails details) {
                if (System.getProperty("broken")) {
                    throw new NullPointerException("meh: error from custom rule")
                }
                details.result.status = 'release'
            }
          }
"""
        setMetadataSupplierClass('MP')

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectGetMetadata()
                }
            }
            'group:projectB' {
                expectVersionListing()
            }
        }
        executer.withArgument("-Dbroken=true")

        then:
        fails 'checkDeps'

        failure.assertHasCause('Could not resolve group:projectB:latest.release')
        failure.assertHasCause('meh: error from custom rule')

        when:
        resetExpectations()
        repositoryInteractions {
            'group:projectB:2.2' {
                expectResolve()
            }
            'group:projectA:1.2' {
                expectGetArtifact()
            }
        }

        then:
        succeeds 'checkDeps'
    }

    def "handles failure to create custom metadata provider"() {
        given:
        buildFile << """
          class MP implements ComponentMetadataSupplier {
            MP() {
                throw new RuntimeException("broken")
            }
          
            void execute(ComponentMetadataSupplierDetails details) {
            }
          }
"""

        setMetadataSupplierClass('MP')

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectGetMetadata()
                }
            }
            'group:projectB' {
                expectVersionListing()
            }
        }

        then:
        fails 'checkDeps'

        failure.assertHasCause('Could not resolve group:projectB:latest.release')
        failure.assertHasCause('Could not create an instance of type MP.')
        failure.assertHasCause('broken')
    }

    @RequiredFeatures([
        @RequiredFeature(feature=GradleMetadataResolveRunner.REPOSITORY_TYPE, value="ivy"),
        @RequiredFeature(feature = GradleMetadataResolveRunner.EXPERIMENTAL_RESOLVE_BEHAVIOR, value = "false"),
    ])
    def "custom metadata provider doesn't have to do something"() {
        given:
        buildFile << """
          class MP implements ComponentMetadataSupplier {
          
            void execute(ComponentMetadataSupplierDetails details) {
                // does nothing
            }
          }
"""
        setMetadataSupplierClass('MP')
        repository {
            'group:projectB:3.3' {
                withModule(IvyHttpModule) {
                    withStatus 'release'
                }
            }
        }

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '3.3' {
                    expectResolve()
                }
            }
        }

        then:
        checkResolve "group:projectA:1.+": "group:projectA:1.2",
            "group:projectB:latest.release": "group:projectB:3.3"
    }

    def "can use a single remote request to get status of multiple components"() {
        given:
        buildFile << """
          class MP implements ComponentMetadataSupplier {
          
            final RepositoryResourceAccessor repositoryResourceAccessor
            
            @Inject
            MP(RepositoryResourceAccessor accessor) { repositoryResourceAccessor = accessor }
            
            int calls
            Map<String, String> status = [:]
          
            void execute(ComponentMetadataSupplierDetails details) {
                def id = details.id
                println "Providing metadata for \$id"
                repositoryResourceAccessor.withResource("status.txt") {
                    if (status.isEmpty()) {
                        println "Parsing status file call count: \${++calls}"
                        it.withReader { reader ->
                            reader.eachLine { line ->
                                if (line) {
                                   def (module, st) = line.split(';')
                                   status[module] = st
                                }
                            }
                        }
                        println status
                    }
                }
                details.result.status = status[id.toString()]
            }
          }
"""
        setMetadataSupplierClass('MP')

        when:
        def statusFile = temporaryFolder.createFile("versions.status")
        statusFile << '''group:projectA:1.1;release
group:projectA:1.2;release
group:projectB:1.1;release
group:projectB:2.2;integration
'''
        server.expectGet("/repo/status.txt", statusFile)
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '1.1' {
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputContains 'Providing metadata for group:projectB:1.1'
        outputDoesNotContain('Providing metadata for group:projectA:1.1')

        and: "remote status file parsed only once"
        outputContains 'Parsing status file call count: 1'
        outputDoesNotContain('Parsing status file call count: 2')

        when: "resolving the same dependencies"
        server.expectHead("/repo/status.txt", statusFile)

        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        then: "should parse the result from cache"
        outputContains('Parsing status file call count: 1')

        when: "force refresh dependencies"
        executer.withArgument("-PrefreshDynamicVersions")
        statusFile.text = '''group:projectA:1.1;release
group:projectA:1.2;release
group:projectB:1.1;release
group:projectB:2.2;release
'''
        resetExpectations()
        // Similarly the HEAD request here is due to revalidating the cached resource
        server.expectHead("/repo/status.txt", statusFile)
        server.expectGet("/repo/status.txt", statusFile)
        repositoryInteractions {
            'group:projectA' {
                expectHeadVersionListing()
            }
            'group:projectB' {
                expectHeadVersionListing()
                '2.2' {
                    expectResolve()
                }
            }
        }

        then: "shouldn't use the cached resource"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:2.2"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputDoesNotContain('Providing metadata for group:projectB:1.1')
        outputDoesNotContain('Providing metadata for group:projectA:1.1')

    }

    def "refresh-dependencies triggers revalidating external resources"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        when:
        repositoryInteractions {
            'group:projectB' {
                expectVersionListing()
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'release')

                    }
                    expectResolve()
                }
            }
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        when:
        executer.withArgument('--refresh-dependencies')

        then:
        resetExpectations()
        repositoryInteractions {
            'group:projectA' {
                expectHeadVersionListing()
                '1.2' {
                    expectHeadMetadata()
                    expectHeadArtifact()
                }
            }
            'group:projectB' {
                expectHeadVersionListing()
                '1.1' {
                    expectHeadMetadata()
                    expectHeadArtifact()
                }
            }
        }
        supplierInteractions.refresh('group:projectB:2.2', 'group:projectB:1.1')
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
    }

    def "component metadata rules are executed after metadata supplier is called"() {
        given:
        def supplierInteractions = withPerVersionStatusSupplier()

        buildFile << """
            dependencies {
                components {
                    withModule('group:projectB') {
                        if (id.version == '1.1') {
                           println("Changing status for \$id from '\$status' to 'release'")
                           status = 'release'
                        }
                    }
                }
            }
       
        """

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'integration')
                    }
                }
                '1.1' {
                    withModule {
                        supplierInteractions.expectGetStatus(delegate, 'should be overriden by rule')

                    }
                    expectResolve()
                }
            }
        }
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"

        then:
        outputContains 'Providing metadata for group:projectB:1.1'
        // first one comes from the rule executed on shallow metadata, provided by a rule
        outputContains "Changing status for group:projectB:1.1 from 'should be overriden by rule' to 'release'"

        // second one comes from the rule executed on "real" metadata, after parsing the module
        outputContains "Changing status for group:projectB:1.1 from '${GradleMetadataResolveRunner.useIvy()?'integration':'release'}' to 'release'"
    }

    def "can use a custom metadata provider to expose components with custom attributes"() {
        given:
        withSupplierWithAttributes([
                'projectA:1.2': [:],
                'projectB:2.2': ['ProjectInternal.STATUS_ATTRIBUTE': '"integration"'],
                'projectB:1.1': ['ProjectInternal.STATUS_ATTRIBUTE': '"release"']
        ])

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2'()
                '1.1' {
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputContains 'Providing metadata for group:projectB:1.1'
    }

    def "can use a custom metadata provider to perform selection using attributes without fetching component metadata"() {
        given:
        withSupplierWithAttributes([
                'projectA:1.2': [:],
                'projectB:2.2': ['ProjectInternal.STATUS_ATTRIBUTE': '"release"', 'MyAttributes.CUSTOM_STR': '"v1"'],
                'projectB:1.1': ['ProjectInternal.STATUS_ATTRIBUTE': '"release"', 'MyAttributes.CUSTOM_STR': '"v2"']
        ])

        buildFile << """
            class MyAttributes {
                public static final CUSTOM_STR = Attribute.of("custom string", String)
            }
            
            configurations.conf.attributes {
                attribute(MyAttributes.CUSTOM_STR, 'v2')
            }
        """

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2'()
                '1.1' {
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputContains 'Providing metadata for group:projectB:1.1'

        // Because the consumer has declared attributes, we now need to call the supplier for projectA too
        outputContains 'Providing metadata for group:projectA:1.2'
    }

    /**
     * Component metadata from an external source only support 2 different types of attributes: boolean or string.
     * Gradle makes the necessary work to coerce those into "real" typed attributes during matching. This test
     * is here to prove that coercion works properly whenever attributes are sourced from a component metadata
     * supplier.
     */
    def "user provided attributes are properly coerced to typed attributes"() {
        given:
        withSupplierWithAttributes([
                'projectA:1.2': [:],
                'projectB:2.2': ['ProjectInternal.STATUS_ATTRIBUTE': '"release"', 'MyAttributes.CUSTOM_STR': '"v1"'],
                'projectB:1.1': ['ProjectInternal.STATUS_ATTRIBUTE': '"release"', 'MyAttributes.CUSTOM_STR': '"v2"']
        ])

        buildFile << """
            interface CustomType extends Named {}
            
            class MyAttributes {
                public static final CUSTOM_STR = Attribute.of("custom", String)
                public static final CUSTOM_REAL = Attribute.of("custom", CustomType)
            }
            
            configurations.conf.attributes {
                attribute(MyAttributes.CUSTOM_REAL, objects.named(CustomType, 'v2'))
            }
        """

        when:
        repositoryInteractions {
            'group:projectA' {
                expectVersionListing()
                '1.2' {
                    expectResolve()
                }
            }
            'group:projectB' {
                expectVersionListing()
                '2.2'()
                '1.1' {
                    expectResolve()
                }
            }
        }

        then: "custom metadata rule prevented parsing of ivy descriptor"
        checkResolve "group:projectA:1.+": "group:projectA:1.2", "group:projectB:latest.release": "group:projectB:1.1"
        outputContains 'Providing metadata for group:projectB:2.2'
        outputContains 'Providing metadata for group:projectB:1.1'

        // Because the consumer has declared attributes, we now need to call the supplier for projectA too
        outputContains 'Providing metadata for group:projectA:1.2'
    }


    private SimpleSupplierInteractions withPerVersionStatusSupplier() {
        buildFile << """
          class MP implements ComponentMetadataSupplier {
          
            final RepositoryResourceAccessor repositoryResourceAccessor
            
            @Inject
            MP(RepositoryResourceAccessor accessor) { repositoryResourceAccessor = accessor }
          
            int count
          
            void execute(ComponentMetadataSupplierDetails details) {
                assert count == 0
                def id = details.id
                println "Providing metadata for \$id"
                repositoryResourceAccessor.withResource("\${id.group}/\${id.module}/\${id.version}/status.txt") {
                    details.result.status = new String(it.bytes)
                }
                count++
            }
          }

"""
        metadataSupplierClass = 'MP'
        new SimpleSupplierInteractions()
    }

    private SupplierInteractions withSupplierWithAttributes(Map<String, Map<String, String>> attributesPerVersion) {
        def cases = attributesPerVersion.collect { version, attributes ->
            def attributesBlock = attributes.collect { k, v ->
                "it.attribute($k, $v)"
            }.join('; ')
            "case ('$version'): details.result.attributes { $attributesBlock }; break"
        }.join('\n                    ')

        buildFile << """
        import org.gradle.api.internal.project.ProjectInternal

        class MP implements ComponentMetadataSupplier {
            void execute(ComponentMetadataSupplierDetails details) {
                def id = details.id
                println "Providing metadata for \$id"
                String key = "\${id.module}:\${id.version}"
                switch (key) {
                    $cases
                }
            }
        }
"""
        metadataSupplierClass = 'MP'
        null
    }

    def checkResolve(Map edges) {
        assert succeeds('checkDeps')
        resolve.expectGraph {
            root(":", ":test:") {
                edges.each { from, to ->
                    edge(from, to)
                }
            }
        }
        true
    }

    interface SupplierInteractions {
        void expectGetStatus(HttpModule module, String status, boolean broken)
        void refresh(String... modules)
    }

    class SimpleSupplierInteractions implements SupplierInteractions {
        private final Map<String, File> statusFiles = [:]

        SimpleSupplierInteractions() {
        }

        @Override
        void expectGetStatus(HttpModule module, String status = 'release', boolean broken = false) {
            def path = pathOf(module)
            statusFiles[path.replace('/', ':')] = expectGetStatusOf(path, status, broken)
        }

        private String pathOf(HttpModule module) {
            if (module instanceof IvyHttpModule) {
                "${module.organisation}/${module.module}/${module.version}"
            } else if (module instanceof MavenHttpModule) {
                "${module.group}/${module.module}/${module.version}"
            }
        }

        private File expectGetStatusOf(String path, String status, boolean broken) {
            def file = temporaryFolder.createFile("cheap-${path.replace('/', '_')}.status")
            file.text = status
            if (!broken) {
                server.expectGet("/repo/${path}/status.txt", file)
            } else {
                server.expectGetBroken("/repo/${path}/status.txt")
            }
            file
        }

        @Override
        void refresh(String... modules) {
            modules.each {
                server.expectHead("/repo/${it.replace(':', '/')}/status.txt", statusFiles[it])
            }
        }
    }
}
