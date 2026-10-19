/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Nov 22 12:20:37 CET 2024                                                 */


#define BN_CTX_new BN_CTX_newptr
#define BN_add BN_addptr
#define BN_bin2bn BN_bin2bnptr
#define BN_bn2bin BN_bn2binptr
#define BN_bn2binpad BN_bn2binpadptr
#define BN_bn2hex BN_bn2hexptr
#define BN_div BN_divptr
#define BN_dup BN_dupptr
#define BN_free BN_freeptr
#define BN_hex2bn BN_hex2bnptr
#define BN_mod_mul BN_mod_mulptr
#define BN_mul BN_mulptr
//#define BN_native2bn BN_native2bnptr
#define BN_new BN_newptr
#define BN_nnmod BN_nnmodptr
#define BN_rand_range BN_rand_rangeptr
#define BN_sub BN_subptr
#define EC_POINT_add EC_POINT_addptr
#define EC_POINT_cmp EC_POINT_cmpptr
#define EC_POINT_dup EC_POINT_dupptr
#define EC_POINT_free EC_POINT_freeptr
#define EC_POINT_get_affine_coordinates EC_POINT_get_affine_coordinatesptr
#define EC_POINT_mul EC_POINT_mulptr
#define EC_POINT_new EC_POINT_newptr
#define EC_POINT_oct2point EC_POINT_oct2pointptr
#define EC_POINT_point2oct EC_POINT_point2octptr
#define EC_POINT_set_affine_coordinates EC_POINT_set_affine_coordinatesptr
#define EVP_DigestFinal EVP_DigestFinalptr
#define EVP_DigestInit EVP_DigestInitptr
#define EVP_DigestUpdate EVP_DigestUpdateptr
#define EVP_MD_CTX_free EVP_MD_CTX_freeptr
#define EVP_MD_CTX_new EVP_MD_CTX_newptr
#define AES_set_encrypt_key AES_set_encrypt_keyptr
#define AES_encrypt AES_encryptptr
#define CRYPTO_free CRYPTO_freeptr

// #define EC_GROUP_get0_order EC_GROUP_get0_orderptr
 #define EC_GROUP_get_order EC_GROUP_get_orderptr
#define ERR_print_errors_cb ERR_print_errors_cbptr
#define EC_GROUP_get0_generator EC_GROUP_get0_generatorptr
#define EC_GROUP_get_curve EC_GROUP_get_curveptr
   #define EC_GROUP_get0_cofactor EC_GROUP_get0_cofactorptr
#define EC_KEY_get0_private_key EC_KEY_get0_private_keyptr
#define EC_KEY_get0_public_key EC_KEY_get0_public_keyptr
 #define EC_KEY_free EC_KEY_freeptr
  #define EC_KEY_new_by_curve_name EC_KEY_new_by_curve_nameptr
  #define EC_KEY_generate_key EC_KEY_generate_keyptr
#define EC_GROUP_new_by_curve_name EC_GROUP_new_by_curve_nameptr

 #define EVP_sha256 EVP_sha256ptr
   #define ERR_clear_error ERR_clear_errorptr


#define EC_GROUP_get_cofactor EC_GROUP_get_cofactorptr


     #define EC_KEY_set_private_key EC_KEY_set_private_keyptr
     #define EC_KEY_set_public_key EC_KEY_set_public_keyptr
   #define ECDSA_size ECDSA_sizeptr
  #define ECDSA_sign ECDSA_signptr
