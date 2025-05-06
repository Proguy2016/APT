# Railway Deployment Guide

This document describes how to deploy the Collaborative Text Editor application to Railway.

## Prerequisites

- A Railway account (https://railway.app/)
- Git installed on your local machine
- Maven installed if building locally

## Deployment Files

The following files are used for Railway deployment:

1. `Dockerfile` - Defines how to build and run the application
2. `railway.json` - Configuration for Railway deployment

## Environment Variables

These environment variables are used by the application:

- `PORT` - The port on which the WebSocket server runs (default: 27017)
- `HOST` - The host interface to bind to (default: 0.0.0.0)
- `MONGODB_URI` - MongoDB connection string
- `MONGODB_DATABASE` - MongoDB database name (default: collaborative_editor)

## Deployment Steps

### Method 1: Deploy via Railway Dashboard

1. Log in to your Railway account
2. Click "New Project"
3. Select "Deploy from GitHub repo"
4. Choose your GitHub repository
5. Railway will automatically detect the Dockerfile and deploy

### Method 2: Deploy via Railway CLI

1. Install the Railway CLI:
   ```
   npm i -g @railway/cli
   ```

2. Login to Railway:
   ```
   railway login
   ```

3. Link to your project:
   ```
   railway link
   ```

4. Deploy your application:
   ```
   railway up
   ```

## Troubleshooting

### Container Crashes on Startup

If the container crashes on startup, check the following:

1. Verify environment variables are set correctly in the Railway dashboard
2. Check the logs for any Java exceptions 
3. Make sure the MongoDB connection string is valid and accessible

### MongoDB Connection Issues

If the application can't connect to MongoDB:

1. Verify the `MONGODB_URI` is correct in the Railway dashboard
2. Check if the MongoDB service is running
3. Confirm the network allows connections from the application to MongoDB

### Port Configuration

The application uses the `PORT` environment variable. Railway automatically assigns this variable.

## Monitoring

Monitor your application through:

1. Railway dashboard - Shows logs and metrics
2. Application logs - Check for MongoDB connection success/failure

## Security Considerations

- Never hard-code sensitive information like database credentials
- Use environment variables for all configuration
- Make sure your MongoDB instance is properly secured

## Additional Notes

- The WebSocket server doesn't respond to HTTP health checks, so these are disabled in the Railway configuration
- When deploying updates, Railway will rebuild and restart your application automatically 