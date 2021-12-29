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

package com.facebook.samples.litho.java.animations.docs;

import static android.graphics.Color.YELLOW;

import android.view.View;
import com.facebook.litho.ClickEvent;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.StateValue;
import com.facebook.litho.Transition;
import com.facebook.litho.animation.AnimatedProperties;
import com.facebook.litho.annotations.FromEvent;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnCreateTransition;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.OnUpdateState;
import com.facebook.litho.annotations.State;
import com.facebook.litho.widget.SolidColor;
import com.facebook.yoga.YogaAlign;

// start
@LayoutSpec
public class AppearTransitionComponentSpec {

  private static final String SQUARE_KEY = "square";

  @OnCreateLayout
  static Component onCreateLayout(ComponentContext c, @State boolean shown) {
    Component child;
    if (shown) {
      child =
          SolidColor.create(c)
              .color(YELLOW)
              .widthDip(80)
              .heightDip(80)
              .transitionKey(SQUARE_KEY)
              .build();
    } else {
      child = null;
    }

    return Column.create(c)
        .heightPercent(100)
        .child(child)
        .clickHandler(AppearTransitionComponent.onClickEvent(c))
        .alignItems(YogaAlign.FLEX_END)
        .build();
  }

  @OnCreateTransition
  static Transition onCreateTransition(ComponentContext c) {
    return Transition.create(SQUARE_KEY)
        .animate(AnimatedProperties.X)
        .appearFrom(0f)
        .animate(AnimatedProperties.ALPHA)
        .appearFrom(0f);
  }

  @OnEvent(ClickEvent.class)
  static void onClickEvent(ComponentContext c, @FromEvent View view) {
    AppearTransitionComponent.onUpdateState(c);
  }

  @OnUpdateState
  static void onUpdateState(StateValue<Boolean> shown) {
    shown.set(!shown.get());
  }
}
// end
