#!/bin/bash

# Script to fix JUnit assertion parameter order from JUnit 4 to JUnit 5 format
# In JUnit 5, the message parameter should be the last parameter

echo "Fixing JUnit assertion parameter order in LRA client test files..."

# Find all test files in the client module
TEST_FILES=$(find /home/msappegr/git/lra/client/src/test/java -name "*.java")

for FILE in $TEST_FILES; do
    echo "Processing: $FILE"

    # Create backup
    cp "$FILE" "$FILE.backup"

    # Fix different assertion types with sed
    # Fix assertNotNull("message", value) -> assertNotNull(value, "message")
    sed -i 's/assertNotNull("\([^"]*\)", \([^)]*\))/assertNotNull(\2, "\1")/g' "$FILE"

    # Fix assertNull("message", value) -> assertNull(value, "message")
    sed -i 's/assertNull("\([^"]*\)", \([^)]*\))/assertNull(\2, "\1")/g' "$FILE"

    # Fix assertTrue("message", condition) -> assertTrue(condition, "message")
    sed -i 's/assertTrue("\([^"]*\)", \([^)]*\))/assertTrue(\2, "\1")/g' "$FILE"

    # Fix assertFalse("message", condition) -> assertFalse(condition, "message")
    sed -i 's/assertFalse("\([^"]*\)", \([^)]*\))/assertFalse(\2, "\1")/g' "$FILE"

    # Fix assertSame("message", expected, actual) -> assertSame(expected, actual, "message")
    sed -i 's/assertSame("\([^"]*\)", \([^,]*\), \([^)]*\))/assertSame(\2, \3, "\1")/g' "$FILE"

    # Fix assertNotSame("message", expected, actual) -> assertNotSame(expected, actual, "message")
    sed -i 's/assertNotSame("\([^"]*\)", \([^,]*\), \([^)]*\))/assertNotSame(\2, \3, "\1")/g' "$FILE"

    # Fix assertEquals("message", expected, actual) -> assertEquals(expected, actual, "message")
    sed -i 's/assertEquals("\([^"]*\)", \([^,]*\), \([^)]*\))/assertEquals(\2, \3, "\1")/g' "$FILE"

    # Fix any syntax errors introduced by sed
    # Fix cases where extra parentheses were added
    sed -i 's/(\([^,)]*\)(, "/(\1, "/g' "$FILE"

    echo "Fixed: $FILE"
done

echo "All assertion parameter orders have been fixed!"
echo "Backup files created with .backup extension"