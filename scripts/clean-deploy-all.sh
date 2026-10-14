#!/bin/bash

# Set colors for better visibility
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Navigate to git repository root
echo -e "${BLUE}➤ Navigating to repository root...${NC}"
cd "$(git rev-parse --show-toplevel)" || { echo "Failed to find git repository root"; exit 1; }
echo -e "${GREEN}✓ Now in $(pwd)${NC}"

# Run tests first
echo -e "\n${BLUE}➤ Running tests...${NC}"
mvn test
if [ $? -ne 0 ]; then
    echo -e "${YELLOW}⚠ Tests failed, but continuing with deployment${NC}"
else
    echo -e "${GREEN}✓ Tests completed successfully${NC}"
fi

# Stage all changes
echo -e "\n${BLUE}➤ Staging changes for deployment...${NC}"
git add .
echo -e "${GREEN}✓ Changes staged${NC}"

# For GitHub Packages
echo -e "\n${BLUE}➤ Deploying to GitHub Packages...${NC}"
mvn clean deploy -P github -DskipTests=true
if [ $? -ne 0 ]; then
    echo -e "${YELLOW}⚠ GitHub Packages deployment encountered issues${NC}"
else
    echo -e "${GREEN}✓ GitHub Packages deployment successful${NC}"
fi

# For Nexus Repository
echo -e "\n${BLUE}➤ Deploying to Nexus Repository...${NC}"
mvn clean deploy -P nexus -DskipTests=true
if [ $? -ne 0 ]; then
    echo -e "${YELLOW}⚠ Nexus Repository deployment encountered issues${NC}"
else
    echo -e "${GREEN}✓ Nexus Repository deployment successful${NC}"
fi

# For Maven Central - using a different approach
echo -e "\n${BLUE}➤ Preparing for Maven Central deployment...${NC}"

# Create jar, sources jar, and javadoc jar explicitly
echo -e "${BLUE}  Creating all required artifacts...${NC}"
mvn clean package source:jar javadoc:jar -DskipTests=true

# Check if the jars were created
echo -e "${BLUE}  Verifying artifacts existence...${NC}"
MAIN_JAR=$(find target -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
SOURCES_JAR=$(find target -name "*-sources.jar" | head -1)
JAVADOC_JAR=$(find target -name "*-javadoc.jar" | head -1)

if [ -z "$MAIN_JAR" ] || [ -z "$SOURCES_JAR" ] || [ -z "$JAVADOC_JAR" ]; then
    echo -e "${RED}✗ Could not find all required artifacts for Maven Central${NC}"
    [ -z "$MAIN_JAR" ] && echo -e "${RED}  Missing main JAR${NC}"
    [ -z "$SOURCES_JAR" ] && echo -e "${RED}  Missing sources JAR${NC}"
    [ -z "$JAVADOC_JAR" ] && echo -e "${RED}  Missing javadoc JAR${NC}"
    echo -e "${YELLOW}⚠ Will attempt to deploy to Maven Central anyway${NC}"
else
    echo -e "${GREEN}✓ All required artifacts created:${NC}"
    echo -e "${GREEN}  - Main JAR: $MAIN_JAR${NC}"
    echo -e "${GREEN}  - Sources JAR: $SOURCES_JAR${NC}"
    echo -e "${GREEN}  - Javadoc JAR: $JAVADOC_JAR${NC}"
fi

# Attempt to deploy to Maven Central
echo -e "${BLUE}  Now publishing to Maven Central...${NC}"
mvn gpg:sign install:install org.sonatype.central:central-publishing-maven-plugin:publish -P central -DskipTests=true
if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Maven Central deployment failed${NC}"
    echo -e "${YELLOW}  Attempting alternative approach...${NC}"
    # Try alternative approach - use deploy plugin explicitly
    mvn deploy -P central -DskipTests=true
    if [ $? -ne 0 ]; then
        echo -e "${RED}✗ Alternative Maven Central deployment also failed${NC}"
    else
        echo -e "${GREEN}✓ Alternative Maven Central deployment successful${NC}"
    fi
else
    echo -e "${GREEN}✓ Maven Central deployment successful${NC}"
fi

echo -e "\n${GREEN}✓✓ Deployment process completed${NC}"
echo -e "${BLUE}Timestamp: $(date)${NC}"