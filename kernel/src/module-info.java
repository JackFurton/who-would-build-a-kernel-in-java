/**
 * The kernel is its own java.base: there is no JDK underneath, so java.lang here is the whole
 * platform the kernel can use.
 */
module java.base {
    exports java.lang;
    exports java.lang.annotation;
    exports java.lang.invoke;
    exports java.util;
    exports duke.rt;
    exports duke.boot;
    exports duke.kernel;
    exports duke.kernel.x86;
}
