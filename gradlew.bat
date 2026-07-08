@rem Gradle startup script for Windows. Requires gradle-wrapper.jar.
@echo off
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
  echo gradle-wrapper.jar missing. Run "gradle wrapper" or open in Android Studio.
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
