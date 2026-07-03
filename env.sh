# Source this before building/running:  source env.sh
# Corretto JDK 8 (Apple Silicon) + Maven were installed under ~/tools by the setup step.
export JAVA_HOME="$HOME/tools/amazon-corretto-8.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
