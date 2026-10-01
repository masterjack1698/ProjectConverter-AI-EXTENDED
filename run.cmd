if "%JAVA_HOME25%"=="" set "JAVA_HOME25=C:\Program Files\Java\jdk-25.0.4.1"
set "JAVA_HOME=%JAVA_HOME25%"
"%JAVA_HOME%\bin\java" --module-path target\lib --module de.mossgrabers.projectconverter
pause
