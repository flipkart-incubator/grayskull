#!/bin/bash -e

# Define color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

if [ ! -f .env ]; then
    echo -e "${YELLOW}INFO: .env file not found. Please copy .env.example to .env and update the credentials.${NC}"
    echo -e "${YELLOW}Attempting to use default environment variables for now, but this is not recommended for production or shared environments.${NC}"
fi

echo -e "${GREEN}Starting MongoDB for Grayskull...${NC}"
docker-compose --env-file .env -p grayskull up -d
echo -e "${GREEN}MongoDB started.${NC}"

echo -e "${GREEN}Updating /etc/hosts...${NC}"
if ! grep -q "host.docker.internal" /etc/hosts; then
  echo "127.0.0.1 host.docker.internal" | sudo tee -a /etc/hosts > /dev/null
  echo -e "${GREEN}Added host.docker.internal to /etc/hosts.${NC}"
else
  echo -e "${YELLOW}host.docker.internal already exists in /etc/hosts.${NC}"
fi

echo -e "${GREEN}Setup complete.${NC}"