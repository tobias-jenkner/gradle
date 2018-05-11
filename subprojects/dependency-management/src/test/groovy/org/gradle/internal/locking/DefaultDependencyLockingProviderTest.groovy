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

package org.gradle.internal.locking

import org.gradle.StartParameter
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint
import org.gradle.api.internal.file.FileResolver
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Subject

import static org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint.strictConstraint

class DefaultDependencyLockingProviderTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    TestFile lockDir = tmpDir.createDir(LockFileReaderWriter.DEPENDENCY_LOCKING_FOLDER)

    FileResolver resolver = Mock()
    StartParameter startParameter = Mock()

    @Subject
    DefaultDependencyLockingProvider provider

    def setup() {
        resolver.canResolveRelativePath() >> true
        resolver.resolve(LockFileReaderWriter.DEPENDENCY_LOCKING_FOLDER) >> lockDir
        startParameter.getLockedDependenciesToUpdate() >> []
        provider = new DefaultDependencyLockingProvider(resolver, startParameter)
    }

    def 'can persist resolved modules as lockfile'() {
        given:
        startParameter.isWriteDependencyLocks() >> true
        provider = new DefaultDependencyLockingProvider(resolver, startParameter)
        def modules = [module('org', 'foo', '1.0'), module('org','bar','1.3')] as Set

        when:
        provider.persistResolvedDependencies('conf', modules)

        then:
        lockDir.file('conf.lockfile').text == """${LockFileReaderWriter.LOCKFILE_HEADER}org:bar:1.3
org:foo:1.0
"""
    }

    def 'can load lockfile as strict constraints'() {
        given:
        lockDir.file('conf.lockfile') << """org:bar:1.3
org:foo:1.0
"""
        when:
        def result = provider.loadLockState('conf')

        then:
        result.mustValidateLockState()
        result.getLockedDependencies() == [strictConstraint('org', 'bar', '1.3'), strictConstraint('org', 'foo', '1.0')] as Set
    }

    def 'can load lockfile as prefer constraints in update mode'() {
        given:
        startParameter = Mock()
        startParameter.isWriteDependencyLocks() >> true
        startParameter.getLockedDependenciesToUpdate() >> ['org:foo']
        provider = new DefaultDependencyLockingProvider(resolver, startParameter)
        lockDir.file('conf.lockfile') << """org:bar:1.3
org:foo:1.0
"""
        when:
        def result = provider.loadLockState('conf')

        then:
        !result.mustValidateLockState()
        result.getLockedDependencies() == [new DefaultDependencyConstraint('org', 'bar', '1.3')] as Set
    }

    def 'can filter lock entries using module update patterns'() {
        given:
        startParameter = Mock()
        startParameter.isWriteDependencyLocks() >> true
        startParameter.getLockedDependenciesToUpdate() >> ['org:*']
        provider = new DefaultDependencyLockingProvider(resolver, startParameter)
        lockDir.file('conf.lockfile') << """org:bar:1.3
org:foo:1.0
"""
        when:
        def result = provider.loadLockState('conf')

        then:
        !result.mustValidateLockState()
        result.getLockedDependencies() == [] as Set
    }

    def 'can filter lock entries using group update patterns'() {
        given:
        startParameter = Mock()
        startParameter.isWriteDependencyLocks() >> true
        startParameter.getLockedDependenciesToUpdate() >> ['org.*:foo']
        provider = new DefaultDependencyLockingProvider(resolver, startParameter)
        lockDir.file('conf.lockfile') << """org.bar:foo:1.3
com:foo:1.0
"""
        when:
        def result = provider.loadLockState('conf')

        then:
        !result.mustValidateLockState()
        result.getLockedDependencies() == [new DefaultDependencyConstraint('com', 'foo', '1.0')] as Set
    }

    def 'fails with invalid content in lock file'() {
        given:
        lockDir.file('conf.lockfile') << """invalid"""

        when:
        provider.loadLockState('conf')

        then:
        def ex = thrown(InvalidLockFileException)
        ex.message == 'Invalid lock file content for configuration \'conf\''
        ex.cause.message == 'The module notation does not respect the lock file format of \'group:name:version\' - received \'invalid\''
    }

    private ModuleComponentIdentifier module(String org, String name, String version) {
        return new DefaultModuleComponentIdentifier(org, name, version)
    }

}
