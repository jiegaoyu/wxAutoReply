package com.example.autoreply;

import java.lang.reflect.Field;
import com.example.autoreply.HookUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SenderLearningHook {

    public static void install(ClassLoader cl) {
        try {
            // 1) 学 g8（消息实体类）——构造时记住 class
            Class<?> g8Class = XposedHelpers.findClassIfExists("com.tencent.mm.storage.g8", cl);
            if (g8Class != null) {
                XposedBridge.hookAllConstructors(g8Class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (MainHook.sKernelMsgEntityCls == null) {
                            MainHook.sKernelMsgEntityCls = param.thisObject.getClass();
                            XposedBridge.log(MainHook.TAG + " [learn] g8 constructed, entity class set: "
                                    + MainHook.sKernelMsgEntityCls.getName());
                        }
                    }
                });
            }

            // 2) 学 i8（发送器），同时打印 Hb 调用前后（Wrapper 版：Boolean, Boolean）
            Class<?> i8Class = XposedHelpers.findClassIfExists("com.tencent.mm.storage.i8", cl);
            if (i8Class != null && g8Class != null) {
                try {
                    XposedHelpers.findAndHookMethod(i8Class, "Hb",
                            g8Class, Boolean.class, Boolean.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    learnSenderIfNeeded(p.thisObject);
                                    Object ent = p.args[0];
                                    String talker  = safeGetString(ent, "field_talker", "talker");
                                    String content = safeGetString(ent, "field_content", "content");
                                    XposedBridge.log(MainHook.TAG + " [Hb.before(wrapper)] talker="
                                            + talker + " text=" + HookUtils.preview(content)
                                            + " a=" + p.args[1] + " b=" + p.args[2]);
                                }
                                @Override protected void afterHookedMethod(MethodHookParam p) {
                                    XposedBridge.log(MainHook.TAG + " [Hb.after(wrapper)] result=" + p.getResult());
                                }
                            });
                } catch (Throwable ignore) {
                    // 某些版本没有这个重载，忽略
                }

                // 3) **新增**：原始型重载（boolean, boolean）
                try {
                    XposedHelpers.findAndHookMethod(i8Class, "Hb",
                            g8Class, boolean.class, boolean.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    learnSenderIfNeeded(p.thisObject);
                                    Object ent = p.args[0];
                                    String talker  = safeGetString(ent, "field_talker", "talker");
                                    String content = safeGetString(ent, "field_content", "content");
                                    XposedBridge.log(MainHook.TAG + " [Hb.before(primitive)] talker="
                                            + talker + " text=" + HookUtils.preview(content)
                                            + " a=" + p.args[1] + " b=" + p.args[2]);
                                }
                                @Override protected void afterHookedMethod(MethodHookParam p) {
                                    XposedBridge.log(MainHook.TAG + " [Hb.after(primitive)] result=" + p.getResult());
                                }
                            });
                } catch (Throwable ignore) {
                    // 没有原始重载也没关系，至少 wrapper 能命中
                }
            }

            XposedBridge.log(MainHook.TAG + " SenderLearningHook installed.");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " SenderLearningHook install failed: " + t);
        }
    }

    private static void learnSenderIfNeeded(Object maybeSender) {
        if (maybeSender != null && MainHook.sKernelMsgSender == null) {
            MainHook.sKernelMsgSender = maybeSender;
            XposedBridge.log(MainHook.TAG + " [learn] sender instance learned: "
                    + maybeSender.getClass().getName());
        }
        // 例如在 SenderLearningHook 里，当 sKernelMsgSender 和 sKernelMsgEntityCls 都已就绪：
if (MainHook.sKernelMsgSender != null && MainHook.sKernelMsgEntityCls != null) {
    KernelSendTracer.install(MainHook.APP_CL);
}

    }

    /** 反射读取 g8 实例上的字符串字段，兼容不同字段名 */
    private static String safeGetString(Object obj, String... names) {
        if (obj == null) return null;
        for (String n : names) {
            try {
                Field f = obj.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof CharSequence) return v.toString();
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignore) {}
        }
        return null;
    }
}

