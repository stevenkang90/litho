/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.rendercore

import android.app.Activity
import android.content.Context
import android.view.View
import com.facebook.rendercore.MountItemsPool.DefaultItemPool
import com.facebook.rendercore.MountItemsPool.ItemPool
import com.facebook.rendercore.MountItemsPool.acquireMountContent
import com.facebook.rendercore.MountItemsPool.clear
import com.facebook.rendercore.MountItemsPool.onContextDestroyed
import com.facebook.rendercore.MountItemsPool.prefillMountContentPool
import com.facebook.rendercore.MountItemsPool.release
import com.facebook.rendercore.MountItemsPool.setMountContentPoolFactory
import org.assertj.core.api.Java6Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class MountItemsPoolTest {

  private val context: Context = RuntimeEnvironment.getApplication()

  private lateinit var activityController: ActivityController<Activity>

  private lateinit var activity: Activity

  @Before
  fun setup() {
    clear()
    setMountContentPoolFactory(null)
    activityController = Robolectric.buildActivity(Activity::class.java).create()
    activity = activityController.get()
  }

  @After
  fun cleanup() {
    setMountContentPoolFactory(null)
  }

  @Test
  fun testPrefillMountContentPool() {
    val prefillCount = 4
    val testRenderUnit = TestRenderUnit(/*id*/ 0, /*customPoolSize*/ prefillCount)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, /*customPoolSize*/ prefillCount)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
    acquireMountContent(context, testRenderUnitToAcquire)
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(1)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPool() {
    val prefillCount = 4
    val customPoolSize = 2
    val testRenderUnit = TestRenderUnit(0, customPoolSize)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    // it is "+ 1" because as soon as it tries to prefill a mount content that doesn't fill the
    // pool, then we stop
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(customPoolSize + 1)
    val testRenderUnitToAcquire = TestRenderUnit(0, 2)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPoolFactory() {
    val customPool: ItemPool = DefaultItemPool(MountItemsPoolTest::class.java, 10)
    setMountContentPoolFactory { customPool }
    val prefillCount = 10
    val testRenderUnit = TestRenderUnit(0, 5)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Java6Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, 5)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
  }

  @Test
  fun testReleaseMountContentForDestroyedContextDoesNothing() {
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit)
    release(activity, testRenderUnit, content1)
    val content2 = acquireMountContent(activity, testRenderUnit)

    // Assert pooling was working before
    Java6Assertions.assertThat(content1).isSameAs(content2)
    release(activity, testRenderUnit, content2)

    // Now destroy the activity and assert pooling no longer works. Next acquire should produce
    // difference content.
    onContextDestroyed(activity)
    val content3 = acquireMountContent(activity, testRenderUnit)
    Java6Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testDestroyingActivityDoesNotAffectPoolingOfOtherContexts() {
    // Destroy activity context
    activityController.destroy()
    onContextDestroyed(activity)
    val testRenderUnit = TestRenderUnit(0)

    // Create content with different context
    val content1 = acquireMountContent(context, testRenderUnit)
    release(context, testRenderUnit, content1)
    val content2 = acquireMountContent(context, testRenderUnit)

    // Ensure different context is unaffected by destroying activity context.
    Java6Assertions.assertThat(content1).isSameAs(content2)
  }

  @Test
  fun testAcquireAndReleaseReturnsCorrectContentInstances() {
    val testRenderUnitToAcquire = TestRenderUnit(/*id*/ 0, /*customPoolSize*/ 2)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // release the second content instance
    release(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should be the same instance that was just released
    Java6Assertions.assertThat(thirdContent).isSameAs(secondContent)
  }

  @Test
  fun testAcquireContentWhenPoolingIsDisabledReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire = TestRenderUnit(/*id*/ 0, /*customPoolSize*/ 0) // disable Pooling

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Java6Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Java6Assertions.assertThat(firstContent).isNotNull
    Java6Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // release the second content instance
    release(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Java6Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  class TestRenderUnit : RenderUnit<View>, ContentAllocator<View> {

    override val id: Long

    private val customPoolSize: Int

    var createdCount: Int
      private set

    constructor(id: Long) : super(RenderType.VIEW) {
      this.id = id
      createdCount = 0
      customPoolSize = ContentAllocator.DEFAULT_MAX_PREALLOCATION
    }

    constructor(id: Long, customPoolSize: Int) : super(RenderType.VIEW) {
      this.id = id
      createdCount = 0
      this.customPoolSize = customPoolSize
    }

    override fun createContent(context: Context): View {
      createdCount++
      return View(context)
    }

    override fun createRecyclingPool(): ItemPool {
      return DefaultItemPool(MountItemsPoolTest::class.java, customPoolSize)
    }

    override val contentAllocator: ContentAllocator<View>
      get() = this

    override fun poolSize(): Int {
      return customPoolSize
    }
  }
}
