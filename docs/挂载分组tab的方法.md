
### 方案1：先尝试执行原长按（反射调用），再执行自定义对话框

```java
void onLoad() {
void onLoad() {
    try {
        File stateFile = new File(getGroupDir(), "selection_state.txt");
        if (stateFile.exists()) {
            stateFile.delete();
        }
    } catch (Exception e) {
        // 忽略清理错误
    }

    try {
        hook = XposedBridge.hookMethod(
            View.class.getDeclaredMethod("onAttachedToWindow"),
            new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (view == null) return;

                    if (view instanceof TextView) {
                        TextView tv = (TextView) view;
                        String text = tv.getText().toString().trim();

                        if (text.isEmpty()) return;

                        View parent = tv.getParent();
                        if (parent != null && parent.getClass().getName().contains("TabLayout$TabView") &&
                            "官方".equals(text)) {

                            view.post(new Runnable() {
                                public void run() {
                                    tv.setOnLongClickListener(new View.OnLongClickListener() {
                                        public boolean onLongClick(View v) {
                                            showAddGroupDialog();
                                            return true;
                                        }
                                    });
                                }
                            });
                        }
                    }
                }
            });
    } catch (Throwable e) {
        uiToast("挂钩失败: " + e.getMessage());
    }
}

void onUnLoad() {
    if (hook != null) {
        hook.unhook();
    }
}
```

### 方案2：用 OnTouchListener 自己判断长按（绕过系统长按事件）

```java
void onLoad() {
    try {
        File stateFile = new File(getGroupDir(), "selection_state.txt");
        if (stateFile.exists()) {
            stateFile.delete();
        }
    } catch (Exception ignored) {}

    try {
        hook = XposedBridge.hookMethod(
            View.class.getDeclaredMethod("onAttachedToWindow"),
            new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (view == null || !(view instanceof TextView)) return;

                    TextView tv = (TextView) view;
                    String text = tv.getText().toString().trim();
                    if (text.isEmpty()) return;

                    View parent = tv.getParent();
                    if (parent == null || !parent.getClass().getName().contains("TabLayout$TabView")) return;

                    if (text.equals("官方")) {
                        view.post(new Runnable() {
                            public void run() {
                                final long[] downTime = {0};

                                tv.setOnTouchListener((v, event) -> {
                                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                                        downTime[0] = System.currentTimeMillis();
                                    } else if (event.getAction() == MotionEvent.ACTION_UP ||
                                               event.getAction() == MotionEvent.ACTION_CANCEL) {
                                        downTime[0] = 0;
                                    }

                                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                                        v.postDelayed(() -> {
                                            if (downTime[0] > 0 && 
                                                System.currentTimeMillis() - downTime[0] >= 500) {
                                                showAddGroupDialog();
                                            }
                                        }, 550);
                                    }

                                    return false;
                                });
                            }
                        });
                    }
                }
            });
    } catch (Throwable e) {
        uiToast("挂钩失败: " + e.getMessage());
    }
}

void onUnLoad() {
    if (hook != null) {
        hook.unhook();
    }
}
```


