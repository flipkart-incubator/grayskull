# Grayskull Local Development Setup

This directory contains scripts to setup Grayskull in local environment.

## Prerequisites

* **Docker** 
* **Docker Compose** 
* **Sudo privileges**

## Setup

1. **Configure Environment Variables**:
   * Navigate to the `setup` directory:
     ```bash
     cd setup
     ```
   
   * Create and configure environment file:
     ```bash
     cp .env.example .env
     ```
   
   * Required environment variables in `.env`:
     * `MONGO_ROOT_USERNAME` - MongoDB root user username
     * `MONGO_ROOT_PASSWORD` - MongoDB root user password

2. **Run the Setup Script**:
   * Ensure you are in the `setup` directory
   * Execute the setup script:
     ```bash
     ./setup.sh
     ```
   * **Note**: This script will:
     * Add required host entries to `/etc/hosts` (requires sudo)
     * Set up a MongoDB replica set with 3 nodes
     * Configure authentication and keyfile-based security

## Exposed Ports

* MongoDB Node 1: `37017` (Primary by default)
* MongoDB Node 2: `37018`
* MongoDB Node 3: `37019`

## Usage

* **Start the Environment**: Run `./setup.sh`
* **Stop and Remove DB containers**: Run `./teardown.sh` (This will remove all containers and data volumes)
* **Connect to MongoDB**:
  ```bash
  mongosh "mongodb://grayskull-local-mongo1:37017,grayskull-local-mongo2:37018,grayskull-local-mongo3:37019/grayskull?replicaSet=rs0" \
    --username $MONGO_ROOT_USERNAME \
    --password $MONGO_ROOT_PASSWORD \
    --authenticationDatabase admin
  ```
