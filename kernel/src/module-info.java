/**
 * The kernel is its own java.base: there is no JDK underneath, so java.lang here is the whole
 * platform the kernel can use.
 */
module java.base {
    exports java.lang;
    exports java.lang.annotation;
    exports duke.rt;
    exports duke.kernel;
}
