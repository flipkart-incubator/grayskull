# Grayskull Local Development Setup

This directory contains scripts to setup Grayskull in local environment

## Prerequisites

*   **Docker** 
*   **Docker Compose**

## Setup

1.  **Configure Environment Variables**:
    *   Navigate to the `setup` directory:
        ```bash
        cd setup
        ```
    
    *   Open `.env` and modify the `MONGO_ROOT_USERNAME` and `MONGO_ROOT_PASSWORD` with your desired credentials. These will be used to initialize the MongoDB root user.

2.  **Run the Setup Script**:
    *   Ensure you are in the `setup` directory.
    *   Execute the setup script:
        ```bash
        ./setup.sh
        ```
    *   **Note**: This script will attempt to add `127.0.0.1 host.docker.internal` to your `/etc/hosts` file. This requires `sudo` privileges, so you might be prompted for your password. This entry helps your application running locally connect to services running inside Docker containers. If the entry already exists, the script will skip this step.

## Usage

*   **Stop and Remove DB containers**: `./teardown.sh` (This will also remove the data volume, so any data stored in DB will be lost).
