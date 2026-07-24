#!/bin/bash
# =============================================================================
# IBM CM Migrator - Compile Script v2.1.31
# Compiles Java sources and creates the executable JAR
# =============================================================================

set -e  # Exit on error

# Ensure we are in the project root
cd "$(dirname "$0")/.."

echo "============================================="
echo " IBM CM Migrator - Compiler v2.1.31"
echo "============================================="

# 1. Java Detection
# Check for local Java 11 first
if [ -f "java_env/jdk-11.0.2/bin/javac" ]; then
    JAVAC_CMD="java_env/jdk-11.0.2/bin/javac"
    JAR_CMD="java_env/jdk-11.0.2/bin/jar"
    echo "Using local Java: $JAVAC_CMD"
else
    JAVAC_CMD="javac"
    JAR_CMD="jar"
    echo "Using system Java: $(which javac 2>/dev/null || echo 'not found')"
fi

# Validate Java compiler
if ! "$JAVAC_CMD" -version &>/dev/null; then
    echo "ERROR: Java compiler not found or not executable!"
    echo "       Please install JDK 11+ or place it in java_env/jdk-11.0.2/"
    exit 1
fi

echo "Java compiler version:"
"$JAVAC_CMD" -version 2>&1 || true

# 2. Source file validation
SRC_DIR="src/com/ibm/ecm/migration"
if [ ! -d "$SRC_DIR" ]; then
    echo "ERROR: Source directory not found: $SRC_DIR"
    exit 1
fi

SOURCE_FILES=$(find "$SRC_DIR" -name "*.java" 2>/dev/null | wc -l)
if [ "$SOURCE_FILES" -eq 0 ]; then
    echo "ERROR: No Java source files found in $SRC_DIR"
    exit 1
fi
echo "Found $SOURCE_FILES Java source files"

# 3. Create target directory
mkdir -p target
mkdir -p bin

# Clean old class files
echo "Cleaning old class files..."
rm -rf target/com 2>/dev/null || true

# 4. Classpath: Include local lib and IBM CM libs
CP="lib/*"
CM_LIB_PATH="/opt/IBM/cm87_api/lib"
if [ -d "$CM_LIB_PATH" ]; then
    CP="$CP:$CM_LIB_PATH/*"
else
    echo "WARNING: CM Library path not found: $CM_LIB_PATH"
    echo "         Using only local libs. Some features may not compile."
fi

# 5. Compile
echo "---------------------------------------------"
echo "Compiling sources..."
echo "Classpath: $CP"
echo "---------------------------------------------"

# Capture compilation output
COMPILE_LOG="compile.log"
if "$JAVAC_CMD" --release 11 -d target -cp "$CP" -Xlint:deprecation -Xlint:unchecked \
    $(find "$SRC_DIR" -name "*.java") 2>&1 | tee "$COMPILE_LOG"; then
    
    # Check if any class files were generated
    CLASS_COUNT=$(find target -name "*.class" 2>/dev/null | wc -l)
    if [ "$CLASS_COUNT" -eq 0 ]; then
        echo "ERROR: Compilation produced no class files!"
        exit 1
    fi
    
    echo "---------------------------------------------"
    echo "Compilation successful! ($CLASS_COUNT class files)"
    
    #6. Create Manifest and JAR
    echo "Creating JAR Manifest..."
    mkdir -p bin/META-INF
    cat > bin/META-INF/MANIFEST.MF << 'EOF'
Manifest-Version: 1.0
Main-Class: com.ibm.ecm.migration.Main
EOF
    
    echo "Creating JAR archive..."
    "$JAR_CMD" cfm bin/cm-migrator.jar bin/META-INF/MANIFEST.MF -C target .
    
    # Display JAR info
    JAR_SIZE=$(ls -lh bin/cm-migrator.jar | awk '{print $5}')
    echo "---------------------------------------------"
    echo "Created: bin/cm-migrator.jar ($JAR_SIZE)"
    echo "============================================="
    echo " Build completed successfully!"
    echo "============================================="
else
    echo "============================================="
    echo " Compilation failed! Check $COMPILE_LOG"
    echo "============================================="
    exit 1
fi
