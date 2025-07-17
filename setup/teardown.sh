#!/bin/bash -e

# Define color codes for output formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Logging function
log() {
    local level=$1
    local message=$2
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo -e "${GREEN}[$timestamp] [INFO] $message${NC}"
}

log "INFO" "Stopping and removing all Grayskull containers and volumes..."
if ! docker-compose --env-file .env -p grayskull down -v; then
    echo -e "${RED}Failed to tear down containers. Please check if Docker is running.${NC}"
    exit 1
fi

log "INFO" "Teardown completed successfully."