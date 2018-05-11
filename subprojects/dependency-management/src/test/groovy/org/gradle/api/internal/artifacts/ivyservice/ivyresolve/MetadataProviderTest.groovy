/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.ivyresolve

import com.google.common.collect.ImmutableMap
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentMetadataSupplier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.artifacts.ComponentMetadataProcessor
import org.gradle.api.internal.artifacts.ivyservice.NamespaceId
import org.gradle.internal.component.external.model.IvyModuleResolveMetadata
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.resolve.result.DefaultBuildableModuleComponentMetaDataResolveResult
import org.gradle.util.TestUtil
import spock.lang.Specification

class MetadataProviderTest extends Specification {
    def dep = Stub(DependencyMetadata)
    def id = Stub(ModuleComponentIdentifier) {
        getGroup() >> 'group'
        getName() >> 'name'
        getVersion() >> "1.2"
    }
    def metaData = Stub(ModuleComponentResolveMetadata)
    def resolveState = Mock(ModuleComponentResolveState)
    def metadataProvider = new MetadataProvider(resolveState)

    def "caches metadata result"() {
        when:
        metadataProvider.getMetaData()
        metadataProvider.getMetaData()

        then:
        1 * resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.resolved(metaData)
            return result
        }
        0 * resolveState.resolve()
    }

    def "verifies that metadata was provided"() {
        given:
        resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.resolved(metaData)
            return result
        }

        expect:
        metadataProvider.resolve()
        metadataProvider.usable
        metadataProvider.metaData
    }

    def "verifies that metadata was not provided"() {
        given:
        resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.missing()
            return result
        }

        expect:
        !metadataProvider.resolve()
        !metadataProvider.usable
    }

    def "can provide component metadata" () {
        given:
        resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.resolved(metaData)
            return result
        }

        when:
        def componentMetadata = metadataProvider.getComponentMetadata()

        then:
        componentMetadata.metadata == metaData
    }

    def "can provide Ivy descriptor" () {
        given:
        def extraInfo = [:]
        extraInfo.put(new NamespaceId("baz", "foo"), "extraInfoValue")

        def metaData = Stub(IvyModuleResolveMetadata)
        metaData.status >> "test"
        metaData.branch >> "branchValue"
        metaData.extraAttributes >> ImmutableMap.copyOf(extraInfo)

        resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.resolved(metaData)
            return result
        }

        when:
        def returned = metadataProvider.getIvyModuleDescriptor()

        then:
        returned.ivyStatus == "test"
        returned.branch == "branchValue"
        returned.extraInfo.get("foo") == "extraInfoValue"
    }

    def "returns null when not Ivy descriptor" () {
        given:
        resolveState.resolve() >> {
            def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
            result.resolved(metaData)
            return result
        }

        expect:
        metadataProvider.getIvyModuleDescriptor() == null
    }

    def "can use a metadata rule to provide metadata"() {
        given:
        resolveState.id >> id
        resolveState.attributesFactory >> TestUtil.attributesFactory()
        resolveState.componentMetadataProcessor >> Mock(ComponentMetadataProcessor) {
            processMetadata(_) >> { args -> args[0] }
        }
        resolveState.componentMetadataSupplier >> Mock(ComponentMetadataSupplier) {
            execute(_) >> { args ->
                def builder = args[0].result
                builder.status = 'foo'
                builder.statusScheme = ['foo', 'bar']
            }
        }

        when:
        def componentMetadata = metadataProvider.componentMetadata

        then:
        0 * resolveState.resolve()
        componentMetadata.status == 'foo'
        componentMetadata.statusScheme == ['foo', 'bar']
    }

    def "can use a component metadata processor to tweak user provided metadata"() {
        def processedMetadata = Mock(ComponentMetadata) {
            getStatus() >> 'from rule'
            getStatusScheme() >> ['from', 'rule']
        }
        given:
        resolveState.id >> id
        resolveState.attributesFactory >> TestUtil.attributesFactory()
        resolveState.componentMetadataProcessor >> Mock(ComponentMetadataProcessor) {
            processMetadata(_) >> { args ->
                processedMetadata
            }
        }
        resolveState.componentMetadataSupplier >> Mock(ComponentMetadataSupplier) {
            execute(_) >> { args ->
                def builder = args[0].result
                builder.status = 'foo'
                builder.statusScheme = ['foo', 'bar']
            }
        }

        when:
        def componentMetadata = metadataProvider.componentMetadata

        then:
        0 * resolveState.resolve()
        componentMetadata.status == 'from rule'
        componentMetadata.statusScheme == ['from', 'rule']
    }


    def "can mutate attributes using a metadata supplier"() {
        def stringAttribute = Attribute.of("test", String)
        def booleanAttribute = Attribute.of("bool", Boolean)
        def unsetAttribute = Attribute.of("unset", String)

        given:
        resolveState.id >> id
        resolveState.attributesFactory >> TestUtil.attributesFactory()
        resolveState.componentMetadataProcessor >> Mock(ComponentMetadataProcessor) {
            processMetadata(_) >> { args -> args[0] }
        }
        resolveState.componentMetadataSupplier >> Mock(ComponentMetadataSupplier) {
            execute(_) >> { args ->
                def builder = args[0].result
                builder.status = 'foo'
                builder.statusScheme = ['foo', 'bar']
                builder.attributes {
                    it.attribute(stringAttribute, "test value")
                    it.attribute(booleanAttribute, true)
                }
            }
        }

        when:
        def componentMetadata = metadataProvider.componentMetadata

        then:
        0 * resolveState.resolve()
        componentMetadata.status == 'foo'
        componentMetadata.statusScheme == ['foo', 'bar']

        and:
        def attributes = componentMetadata.attributes
        attributes.getAttribute(stringAttribute) == 'test value'
        attributes.getAttribute(booleanAttribute) == true
        attributes.getAttribute(unsetAttribute) == null
    }

    def "validates that user supplied attributes are desugared"() {
        def stringAttribute = Attribute.of("test", String)
        def booleanAttribute = Attribute.of("bool", Boolean)
        def invalidAttribute1 = Attribute.of("integer", Integer)
        def invalidAttribute2 = Attribute.of("long", Long)

        given:
        resolveState.id >> id
        resolveState.attributesFactory >> TestUtil.attributesFactory()
        resolveState.componentMetadataProcessor >> Mock(ComponentMetadataProcessor) {
            processMetadata(_) >> { args -> args[0] }
        }
        resolveState.componentMetadataSupplier >> Mock(ComponentMetadataSupplier) {
            execute(_) >> { args ->
                def builder = args[0].result
                builder.status = 'foo'
                builder.statusScheme = ['foo', 'bar']
                builder.attributes {
                    it.attribute(stringAttribute, "test value")
                    it.attribute(booleanAttribute, true)
                    it.attribute(invalidAttribute1, 123)
                }
                builder.attributes.attribute(invalidAttribute2, 456L)
            }
        }

        when:
        metadataProvider.componentMetadata

        then:
        InvalidUserDataException ex = thrown()
        ex.message == """Invalid attributes types have been provider by component metadata supplier. Attributes must either be strings or booleans:
  - Attribute 'integer' has type class java.lang.Integer
  - Attribute 'long' has type class java.lang.Long"""
    }

}
