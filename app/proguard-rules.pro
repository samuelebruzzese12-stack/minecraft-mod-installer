# La build di rilascio non usa la minificazione (isMinifyEnabled = false): questo file è un segnaposto.
# Se un giorno si attiva R8, conservare le librerie di lettura archivi:
# -keep class org.apache.commons.compress.** { *; }
# -keep class com.github.junrar.** { *; }
# -dontwarn org.slf4j.**
# JAVA TO BEDROCK legge le sue tabelle (oggetti di Bedrock, nomi Java→Bedrock) come risorse accanto alle classi:
# -keep class com.mmi.modinstaller.convert.** { *; }
