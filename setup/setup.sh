#!/bin/bash -e

# Define color codes for output formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Logging function for consistent output formatting
log() {
    local level=$1
    local message=$2
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    case $level in
        "INFO")
            echo -e "${GREEN}[$timestamp] [INFO] $message${NC}"
            ;;
        "WARN")
            echo -e "${YELLOW}[$timestamp] [WARN] $message${NC}"
            ;;
        "ERROR")
            echo -e "${RED}[$timestamp] [ERROR] $message${NC}"
            ;;
    esac
}

# Function to update hosts file entries
update_hosts_entry() {
    local entry=$1
    if ! grep -q "$entry" /etc/hosts; then
        if ! echo "$entry" | sudo tee -a /etc/hosts > /dev/null; then
            log "ERROR" "Failed to update /etc/hosts. Please check your sudo privileges."
            exit 1
        fi
        log "INFO" "Added '$entry' to /etc/hosts."
    else
        log "INFO" "'$entry' already exists in /etc/hosts."
    fi
}

# Check for required files and permissions
if [ ! -f .env ]; then
    log "WARN" ".env file not found. Please copy .env.example to .env and update the credentials."
    log "WARN" "Attempting to use default environment variables for now, but this is not recommended for production or shared environments."
fi

# Check and set up MongoDB keyfile
log "INFO" "Setting up MongoDB Keyfile..."
if [ ! -f mongo-keyfile/keyfile ]; then
    log "ERROR" "MongoDB keyfile not found at mongo-keyfile/keyfile"
    exit 1
fi

# Get file permissions in a cross-platform way
file_perms=$(ls -l mongo-keyfile/keyfile | awk '{print $1}')
if [[ ! "$file_perms" =~ "r--------" ]]; then
    if ! chmod 400 mongo-keyfile/keyfile; then
        log "ERROR" "Failed to set permissions on mongo-keyfile/keyfile"
        exit 1
    fi
    log "WARN" "Changed permissions of mongo-keyfile/keyfile to 400."
else
    log "INFO" "mongo-keyfile/keyfile already has permission 400."
fi

# Update /etc/hosts entries for local development
log "INFO" "Updating /etc/hosts entries..."
update_hosts_entry "127.0.0.1 host.docker.internal"
update_hosts_entry "127.0.0.1 grayskull-local-mongo1 grayskull-local-mongo2 grayskull-local-mongo3"

# Start MongoDB containers
log "INFO" "Starting MongoDB for Grayskull..."
if ! docker-compose --env-file .env -p grayskull up --wait -d; then
    log "ERROR" "Failed to start MongoDB containers"
    exit 1
fi

# Wait for MongoDB to be ready
log "INFO" "Waiting for MongoDB to be ready..."
max_attempts=30
attempt=1
while [ $attempt -le $max_attempts ]; do
    if docker exec grayskull-local-mongo1 mongosh --quiet --eval "1" >/dev/null 2>&1; then
        break
    fi
    log "INFO" "Waiting for MongoDB to be ready (attempt $attempt/$max_attempts)..."
    sleep 2
    attempt=$((attempt + 1))
done

if [ $attempt -gt $max_attempts ]; then
    log "ERROR" "MongoDB failed to start within the expected time."
    exit 1
fi

# Initialize MongoDB replica set
(
    # Source environment variables
    if [ -f .env ]; then
        source .env
    fi

    log "INFO" "Initializing MongoDB replica set..."
    if ! docker exec grayskull-local-mongo1 mongosh --username "$MONGO_ROOT_USERNAME" --password "$MONGO_ROOT_PASSWORD" --eval '
        rs.initiate({
            _id: "rs0",
            members: [
                {_id: 0, host: "grayskull-local-mongo1:27017", priority: 2},
                {_id: 1, host: "grayskull-local-mongo2:27017", priority: 1},
                {_id: 2, host: "grayskull-local-mongo3:27017", priority: 1}
            ]
        })
    '; then
        log "ERROR" "Failed to initialize replica set"
        exit 1
    fi
    
    log "INFO" "Waiting for replica set to stabilize..."
    sleep 5
    
    # Verify replica set status
    if ! docker exec grayskull-local-mongo1 mongosh --username "$MONGO_ROOT_USERNAME" --password "$MONGO_ROOT_PASSWORD" --eval "rs.status()"; then
        log "ERROR" "Failed to verify replica set status"
        exit 1
    fi
)

# Print success message and connection information
log "INFO" "MongoDB replica set has been initialized and is ready."
log "INFO" "You can now connect to MongoDB using:"
log "INFO" "mongosh \"mongodb://grayskull-local-mongo1:37017,grayskull-local-mongo2:37018,grayskull-local-mongo3:37019/grayskull?replicaSet=rs0\" --username \$MONGO_ROOT_USERNAME --password \$MONGO_ROOT_PASSWORD --authenticationDatabase admin"

log "INFO" "Setup completed successfully."