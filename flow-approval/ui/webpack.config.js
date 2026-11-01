const path = require('path');
const { VueLoaderPlugin } = require('vue-loader');
const HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = (env, argv) => {
  const isProd = argv.mode === 'production';
  return {
    entry: path.resolve(__dirname, 'src/main.js'),
    output: {
      path: path.resolve(__dirname, 'dist'),
      filename: isProd ? 'js/[name].[contenthash:8].js' : 'js/[name].js',
      chunkFilename: isProd ? 'js/[name].[contenthash:8].js' : 'js/[name].js',
      publicPath: '/',
      clean: true
    },
    resolve: {
      extensions: ['.js', '.vue', '.json'],
      alias: {
        '@': path.resolve(__dirname, 'src'),
        vue$: 'vue/dist/vue.esm.js'
      }
    },
    module: {
      rules: [
        {
          test: /\.vue$/,
          loader: 'vue-loader'
        },
        {
          test: /\.js$/,
          exclude: /node_modules/,
          loader: 'babel-loader'
        },
        {
          test: /\.css$/,
          use: ['style-loader', 'css-loader']
        },
        {
          test: /\.(png|jpe?g|gif|svg|ico)$/i,
          type: 'asset/resource',
          generator: { filename: 'assets/[name].[hash:8][ext]' }
        },
        {
          test: /\.(woff2?|eot|ttf|otf)$/i,
          type: 'asset/resource',
          generator: { filename: 'fonts/[name].[hash:8][ext]' }
        }
      ]
    },
    plugins: [
      new VueLoaderPlugin(),
      new HtmlWebpackPlugin({
        template: path.resolve(__dirname, 'public/index.html'),
        filename: 'index.html',
        inject: 'body'
      })
    ],
    optimization: {
      splitChunks: {
        chunks: 'all'
      }
    },
    devtool: isProd ? false : 'eval-cheap-module-source-map',
    devServer: {
      port: 3000,
      hot: true,
      historyApiFallback: true,
      proxy: {
        '/auth': { target: 'http://localhost:8080', changeOrigin: true },
        '/flow': { target: 'http://localhost:8080', changeOrigin: true },
        '/act': { target: 'http://localhost:8080', changeOrigin: true },
        '/service': { target: 'http://localhost:8080', changeOrigin: true },
        '/sys': { target: 'http://localhost:8080', changeOrigin: true }
      }
    },
    performance: { hints: false }
  };
};
