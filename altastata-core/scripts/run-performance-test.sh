#!/bin/bash
# Copyright (c) 2026 AltaStata Inc. All rights reserved.
#
# This software is dual-licensed. It is licensed under the Business Source License 1.1
# (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0
# license on the Change Date.
#
# PATENT NOTICE: Protected by US Patent No. 10,693,660.
#
# For the full license text, see the LICENSE.md file in the root of the repository,
# or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md

# Performance Test Runner Script
# Compares Google Cloud Storage vs AltaStata performance for file upload/download

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$PROJECT_DIR/src/test/java/com/altastata/performance"
BUILD_DIR="$PROJECT_DIR/build"

echo -e "${BLUE}=== AltaStata Performance Test Runner ===${NC}"
echo

# Check if we're in the right directory
if [ ! -f "$PROJECT_DIR/build.gradle" ]; then
    echo -e "${RED}❌ Error: Not in the correct project directory${NC}"
    echo "Please run this script from the altastata-core directory"
    exit 1
fi

# Function to check if Google Cloud credentials are set
check_gcp_credentials() {
    echo -e "${YELLOW}Checking Google Cloud credentials...${NC}"
    
    if [ -z "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
        echo -e "${YELLOW}⚠️  GOOGLE_APPLICATION_CREDENTIALS not set${NC}"
        echo "Please set your Google Cloud service account key:"
        echo "export GOOGLE_APPLICATION_CREDENTIALS=/path/to/your/service-account-key.json"
        echo
        read -p "Do you want to continue without GCS testing? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
        SKIP_GCS=true
    else
        echo -e "${GREEN}✅ Google Cloud credentials found${NC}"
        SKIP_GCS=false
    fi
}

# Function to compile the project
compile_project() {
    echo -e "${YELLOW}Compiling project...${NC}"
    
    cd "$PROJECT_DIR"
    ./gradlew compileJava compileTestJava
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Compilation successful${NC}"
    else
        echo -e "${RED}❌ Compilation failed${NC}"
        exit 1
    fi
}

# Function to generate test files
generate_test_files() {
    echo -e "${YELLOW}Generating test files...${NC}"
    
    # Create test files directory
    TEST_FILES_DIR="$PROJECT_DIR/test-files"
    mkdir -p "$TEST_FILES_DIR"
    
    # Run the TestFileGenerator
    cd "$PROJECT_DIR"
    ./gradlew test --tests "com.altastata.performance.TestFileGenerator" -q
    
    echo -e "${GREEN}✅ Test files generated in $TEST_FILES_DIR${NC}"
}

# Function to run performance tests
run_performance_tests() {
    echo -e "${YELLOW}Running performance tests...${NC}"
    
    cd "$PROJECT_DIR"
    
    if [ "$SKIP_GCS" = true ]; then
        echo -e "${YELLOW}Skipping GCS tests due to missing credentials${NC}"
        # Run only file generation tests
        ./gradlew test --tests "com.altastata.performance.TestFileGenerator" -q
    else
        # Run full performance tests
        ./gradlew test --tests "com.altastata.performance.FilePerformanceTest" -q
    fi
    
    echo -e "${GREEN}✅ Performance tests completed${NC}"
}

# Function to show test results
show_results() {
    echo -e "${BLUE}=== Test Results Summary ===${NC}"
    echo
    
    # Check if test results exist
    if [ -f "$BUILD_DIR/test-results/test/TEST-com.altastata.performance.FilePerformanceTest.xml" ]; then
        echo -e "${GREEN}✅ Performance test results available${NC}"
        echo "Check the test report for detailed results"
    else
        echo -e "${YELLOW}⚠️  No test results found${NC}"
    fi
    
    # Show generated test files
    if [ -d "$PROJECT_DIR/test-files" ]; then
        echo
        echo -e "${BLUE}Generated Test Files:${NC}"
        ls -lh "$PROJECT_DIR/test-files" | grep -E "\.(txt|bin)$"
    fi
}

# Function to clean up
cleanup() {
    echo -e "${YELLOW}Cleaning up...${NC}"
    
    # Remove test files
    if [ -d "$PROJECT_DIR/test-files" ]; then
        rm -rf "$PROJECT_DIR/test-files"
        echo -e "${GREEN}✅ Test files cleaned up${NC}"
    fi
    
    # Clean build
    cd "$PROJECT_DIR"
    ./gradlew clean -q
    echo -e "${GREEN}✅ Build cleaned up${NC}"
}

# Main execution
main() {
    echo -e "${BLUE}Starting performance test setup...${NC}"
    
    # Check GCP credentials
    check_gcp_credentials
    
    # Compile project
    compile_project
    
    # Generate test files
    generate_test_files
    
    # Run performance tests
    run_performance_tests
    
    # Show results
    show_results
    
    echo
    echo -e "${GREEN}🎉 Performance test completed successfully!${NC}"
    echo
    echo -e "${BLUE}Next steps:${NC}"
    echo "1. Review the test results in the build directory"
    echo "2. Compare performance metrics between GCS and AltaStata"
    echo "3. Analyze throughput and latency for different file sizes"
    echo "4. Consider running tests with different network conditions"
}

# Handle command line arguments
case "${1:-}" in
    "clean")
        cleanup
        ;;
    "generate-files")
        generate_test_files
        ;;
    "run-tests")
        run_performance_tests
        ;;
    "help"|"-h"|"--help")
        echo "Usage: $0 [command]"
        echo
        echo "Commands:"
        echo "  (no args)  Run full performance test suite"
        echo "  clean       Clean up test files and build artifacts"
        echo "  generate-files  Generate test files only"
        echo "  run-tests   Run performance tests only"
        echo "  help        Show this help message"
        ;;
    *)
        main
        ;;
esac 
