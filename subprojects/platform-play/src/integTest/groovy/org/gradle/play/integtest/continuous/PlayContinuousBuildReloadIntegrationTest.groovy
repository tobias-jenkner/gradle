/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.play.integtest.continuous
/**
 * Test Play reload with `--continuous`
 */
class PlayContinuousBuildReloadIntegrationTest extends AbstractPlayReloadIntegrationTest {

    protected static final String PENDING_DETECTED_MESSAGE = 'Pending changes detected'

    def setup() {
        server.start()
        addPendingChangesHook()
    }

    protected void changeAndWaitForPending(Closure changer) {
        def changeDelivered = changesReported()
        changer()
        changeDelivered.waitForAllPendingCalls()
    }

    def "should reload modified scala controller and routes and restart server"() {
        when:
        succeeds("runPlayBinary")

        then:
        appIsRunningAndDeployed()

        when:
        changeAndWaitForPending {
            addNewRoute('hello')
        }
        def page = runningApp.playUrl('hello').text
        serverRestart()

        then:
        page == 'hello world'
    }

    def "should reload with exception when modify scala controller and restart server"() {
        when:
        succeeds("runPlayBinary")
        then:
        appIsRunningAndDeployed()

        when:
        changeAndWaitForPending {
            addBadCode()
        }
        then:
        println "CHECKING ERROR PAGE"
        errorPageHasTaskFailure("compilePlayBinaryScala")
        waitForBuild()
        serverStartCount == 1
        !executedTasks.contains('runPlayBinary')

        when:
        changeAndWaitForPending {
            fixBadCode()
        }

        runningApp.playUrl().text
        serverRestart()
        println "CHANGES DETECTED IN BUILD"

        then:
        appIsRunningAndDeployed()
    }

    def "should reload modified coffeescript but not restart server"() {
        when:
        succeeds("runPlayBinary")

        then:
        appIsRunningAndDeployed()
        !runningApp.playUrl('assets/javascripts/test.js').text.contains('Hello coffeescript')
        !runningApp.playUrl('assets/javascripts/test.min.js').text.contains('Hello coffeescript')

        when:
        changeAndWaitForPending {
            file("app/assets/javascripts/test.coffee") << '''
message = "Hello coffeescript"
alert message
'''
        }

        def testJs = runningApp.playUrl('assets/javascripts/test.js').text
        def testMinJs = runningApp.playUrl('assets/javascripts/test.min.js').text
        noServerRestart()

        then:
        testJs.contains('Hello coffeescript')
        testMinJs.contains('Hello coffeescript')
    }

    def "should detect new javascript files but not restart"() {
        when:
        succeeds("runPlayBinary")

        then:
        appIsRunningAndDeployed()

        when:
        changeAndWaitForPending {
            file("app/assets/javascripts/helloworld.js") << '''
var message = "Hello JS";
'''
        }

        def helloworldJs = runningApp.playUrl('assets/javascripts/helloworld.js').text
        def helloworldMinJs = runningApp.playUrl('assets/javascripts/helloworld.min.js').text
        noServerRestart()

        then:
        helloworldJs.contains('Hello JS')
        helloworldMinJs.contains('Hello JS')
    }

    def "should reload modified java model and restart server"() {
        when:
        succeeds("runPlayBinary")

        then:
        appIsRunningAndDeployed()
        assert runningApp.playUrl().text.contains("<li>foo:1</li>")

        when:
        changeAndWaitForPending {
            file("app/models/DataType.java").with {
                text = text.replaceFirst(~/"%s:%s"/, '"Hello %s:%s !"')
            }
        }

        def page = runningApp.playUrl().text
        serverRestart()

        then:
        page.contains("<li>Hello foo:1 !</li>")
    }

    def "should reload twirl template and restart server"() {
        when:
        succeeds("runPlayBinary")

        then:
        appIsRunningAndDeployed()

        when:
        changeAndWaitForPending {
            file("app/views/index.scala.html").with {
                text = text.replaceFirst(~/Welcome to Play/, 'Welcome to Play with Gradle')
            }
        }
        def page = runningApp.playUrl().text
        serverRestart()

        then:
        page.contains("Welcome to Play with Gradle")
    }

    def "should reload with exception when task that depends on runPlayBinary fails"() {
        given:
        buildFile << """
task otherTask {
   dependsOn 'runPlayBinary'
   doLast {
      // second time through this route exists
      if (file("conf/routes").text.contains("/hello")) {
         throw new GradleException("always fails")
      }
   }
}
"""
        when:
        succeeds("otherTask")
        then:
        appIsRunningAndDeployed()

        when:
        changeAndWaitForPending {
            addNewRoute('hello')
        }

        then:
        errorPageHasTaskFailure("otherTask")
        !executedTasks.contains('runPlayBinary')
    }
}
