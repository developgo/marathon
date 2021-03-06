---
title: Using a Private Docker Registry
---

# Using a Private Docker Registry

As of Marathon 1.5, you can upload your private Docker registry credentials to a secret store, then reference them in your app or pod definition. This functionality is only available if you are using the Mesos containerizer. If you are using the Docker containerizer, follow [these instructions](#docker-containerizer) to use a private Docker registry. If you want to learn how to configure credentials to pull images from the Amazon Elastic Container Registry (AWS ECR) please refer to [this blog post](https://aws.amazon.com/blogs/apn/automating-ecr-authentication-on-marathon-with-the-amazon-ecr-credential-helper/).

## Step 1: Create a Credentials File

1.  Log in to your private registry manually. This will create a `~/.docker` directory and a `~/.docker/config.json` file.

    ```bash
    $ docker login some.docker.host.com
    Username: foo
    Password:
    Email: foo@bar.com
    ```

1.  Check that you have the `~/.docker/config.json` file.

    ```bash
    $ ls ~/.docker
    config.json
    ```

    Your `config.json` file should look like this, where value of `auth` is a based64-encoded `username:password` string. You can generate it using `echo -n 'username:password' | base64`.

    ```json
    {
      "auths": {
          "https://index.docker.io/v1/": {
              "auth": "XXXXX",
              "email": "<your-email>"
          }
      }
    }
    ```

1.  Add the `config.json` file to a secret store. If you are using Enterprise DC/OS, [follow these instructions to add the file to the DC/OS secret store](https://docs.mesosphere.com/latest/security/ent/secrets/create-secrets/#creating-secrets).

### Step 2: Add the Secret to your App or Pod Definition

#### For an Application

Add the following two parameters to your app definition.

1.  A location for the secret in the `secrets` parameter:

    ```json
    "secrets": {
      "pullConfigSecret": {
        "source": "/mesos-docker/pullConfig"
      }
    }
    ```

1.  A reference to the secret in the `docker.pullConfig` parameter:

    ```json
    "docker": {
      "image": "mesosphere/inky",
      "pullConfig": {
        "secret": "pullConfigSecret"
      }
    }
    ```

    **Note:** This functionality is _only_ supported with the Mesos containerizer: `container.type` must be `MESOS`.

1.  A complete example:

    ```json
    {
      "id": "/mesos-docker",
      "container": {
        "docker": {
          "image": "your/private/image",
          "pullConfig": {
            "secret": "pullConfigSecret"
          }
        },
        "type": "MESOS"
      },
      "secrets": {
        "pullConfigSecret": {
          "source": "/mesos-docker/pullConfig"
        }
      },
      "args": ["hello"],
      "cpus": 0.2,
      "mem": 16.0,
      "instances": 1
    }
    ```

1.  The Docker image will now pull using the provided security credentials given.

#### For a Pod

Add the following two parameters to your pod definition.

1.  A location for the secret in the `secrets` parameter:

    ```json
    "secrets": {
      "pullConfigSecret": {
        "source": "/pod/pullConfig"
      }
    }
    ```

1.  A reference to the secret in the `containers.image.pullConfig` parameter:

    ```json
    "containers": [
      {
        "image": {
          "id": "nginx",
          "pullConfig": {
            "secret": "pullConfigSecret"
          },
          "kind": "DOCKER"
        }
      }
    ]
    ```

    **Note:** This functionality is only supported if `image.kind` is set to `DOCKER`.

  1.  A complete example:

      ```json
      {
        "id": "/pod",
        "scaling": { "kind": "fixed", "instances": 1 },
        "containers": [
          {
            "name": "sleep1",
            "exec": { "command": { "shell": "sleep 1000" } },
            "resources": { "cpus": 0.1, "mem": 32 },
            "image": {
              "id": "nginx",
              "pullConfig": {
                "secret": "pullConfigSecret"
              },
              "kind": "DOCKER"
            },
            "endpoints": [ { "name": "web", "containerPort": 80, "protocol": [ "http" ] } ],
            "healthCheck": { "http": { "endpoint": "web", "path": "/ping" } }
          }
        ],
        "networks": [ { "mode": "container", "name": "my-virtual-network-name" } ],
        "secrets": { "pullConfigSecret": { "source": "/pod/pullConfig" } }
      }
      ```

<a name="docker-containerizer"></a>

## Use a Private Docker Registry with the Docker Containerizer

### Registry  1.0 - Docker pre 1.6

To supply credentials to pull from a private registry, add a `.dockercfg` to
the `uris` field of your app. The `$HOME` environment variable will then be set
to the same value as `$MESOS_SANDBOX` so Docker can automatically pick up the
config file.

### Registry  2.0 - Docker 1.6 and up

To supply credentials to pull from a private registry, add a `docker.tar.gz` file to
the `uris` field of your app. The `docker.tar.gz` file should include the `.docker` directory and the contained `.docker/config.json`

#### Step 1: Compress Docker credentials

1.  Log in to the private registry manually. Login creates a `~/.docker` directory and a `~/.docker/config.json` file in your home directory.

    <div class="alert alert-info">
    <strong>Info:</strong> Executing <code>docker login</code> will append credentials to the file and won't replace the old ones. Credentialas will be stored unencrypted.
    </div>

    ```bash
    $ docker login some.docker.host.com
      Username: foo
      Password:
      Email: foo@bar.com
    ```

    Also note that by default, the credentials are not stored in the file, but rather in key store managed by the OS (e.g. `oskeychain` for OSX, `pass` for Linux).
    Make sure the credentials are stored in the file. The new authentication should look like this:
    ```json
    "https://some.docker.host.com": {
        "auth": "XXXXX",
        "email": "<your-email>"
    }
    ```

    Where value of `auth` is a based64-encoded `username:password` string. You can generate it using `echo -n 'username:password' | base64`.

1.  Compress the `~/.docker` directory and its contents.

    ```bash
    $ cd ~
    $ tar -czf docker.tar.gz .docker
    ```
1.  Verify that both files are in the archive.

    ```bash
    $ tar -tvf ~/docker.tar.gz

      drwx------ root/root         0 2015-07-28 02:54 .docker/
      -rw------- root/root       114 2015-07-28 01:31 .docker/config.json
    ```

1.  Put the archive file in a location that is accessible to your application definition.

    ```bash
    $ cp docker.tar.gz /etc/
    ```

     <strong>Note:</strong>
     The URI must be accessible by all nodes that will start your application.
     You can distribute the file to the local filesystem of all nodes, for example via RSYNC/SCP, or store it on a shared network drive like [Google Cloud Storage](https://cloud.google.com/storage/) or [Amazon S3](https://aws.amazon.com/s3/). Consider the security implications of your chosen approach carefully.

<div class="alert alert-warning">
  <strong>Careful:</strong> If you run these commands locally with already installed docker, the resulting tar 
  archive might contain credentials to other registries.
</div>

#### Step 2:  Add URI path to app definition

1.  Add the path to the archive file login credentials to the `fetch` parameter of your app definition.

    ```json
    "fetch": [
      {
        "uri": "file:///etc/docker.tar.gz"
      }
    ]
    ```

1.  For example:

    ```json
    {  
      "id": "/some/name/or/id",
      "cpus": 1,
      "mem": 1024,
      "instances": 1,
      "container": {
        "type": "DOCKER",
        "docker": {
          "image": "some.docker.host.com/namespace/repo",
          "network": "HOST"
        }
      },
      "fetch": [
        {
          "uri": "file:///etc/docker.tar.gz"
        }
      ]
    }
    ```

1.  The Docker image will now pull using the provided security credentials given.

## More information

Find out how to [set up a private Docker registry](https://dcos.io/docs/1.8/usage/tutorials/registry/) with DC/OS.
