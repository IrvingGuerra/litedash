# Reglas de R8 para LiteDash.

# Sin los avisos de logcat en release: nadie los lee en una pantalla de auto (lo que sirve va al reporte técnico) y
# así R8 quita las llamadas y los textos que arman. Ninguna llamada usa el valor que regresan.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static boolean isLoggable(java.lang.String, int);
}
